/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen.inline

import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.codegen.ClosureCodegen
import org.jetbrains.kotlin.codegen.StackValue
import org.jetbrains.kotlin.codegen.intrinsics.IntrinsicMethods
import org.jetbrains.kotlin.codegen.optimization.FixStackWithLabelNormalizationMethodTransformer
import org.jetbrains.kotlin.codegen.optimization.common.InsnSequence
import org.jetbrains.kotlin.codegen.optimization.common.isMeaningful
import org.jetbrains.kotlin.codegen.optimization.fixStack.top
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.SmartSet
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import org.jetbrains.org.objectweb.asm.commons.RemappingMethodAdapter
import org.jetbrains.org.objectweb.asm.tree.*
import org.jetbrains.org.objectweb.asm.tree.analysis.*
import org.jetbrains.org.objectweb.asm.util.Printer
import java.util.*

class MethodInliner(
        private val node: MethodNode,
        private val parameters: Parameters,
        private val inliningContext: InliningContext,
        private val nodeRemapper: FieldRemapper,
        private val isSameModule: Boolean,
        private val errorPrefix: String,
        private val sourceMapper: SourceMapper,
        private val inlineCallSiteInfo: InlineCallSiteInfo,
        private val inlineOnlySmapSkipper: InlineOnlySmapSkipper? //non null only for root
) {
    private val typeMapper = inliningContext.state.typeMapper
    private val invokeCalls = ArrayList<InvokeCall>()
    //keeps order
    private val transformations = ArrayList<TransformationInfo>()
    //current state
    private val currentTypeMapping = HashMap<String, String>()
    private val result = InlineResult.create()
    private var lambdasFinallyBlocks: Int = 0

    fun doInline(
            adapter: MethodVisitor,
            remapper: LocalVarRemapper,
            remapReturn: Boolean,
            labelOwner: LabelOwner
    ): InlineResult {
        return doInline(adapter, remapper, remapReturn, labelOwner, 0)
    }

    private fun doInline(
            adapter: MethodVisitor,
            remapper: LocalVarRemapper,
            remapReturn: Boolean,
            labelOwner: LabelOwner,
            finallyDeepShift: Int
    ): InlineResult {
        //analyze body
        var transformedNode = markPlacesForInlineAndRemoveInlinable(node, labelOwner, finallyDeepShift)
        if (inliningContext.isInliningLambda && isDefaultLambdaWithReification(inliningContext.lambdaInfo!!)) {
            //TODO maybe move reification in one place
            inliningContext.root.inlineMethodReifier.reifyInstructions(transformedNode)
        }

        //substitute returns with "goto end" instruction to keep non local returns in lambdas
        val end = Label()
        transformedNode = doInline(transformedNode)
        removeClosureAssertions(transformedNode)
        transformedNode.instructions.resetLabels()

        val resultNode = MethodNode(
                API, transformedNode.access, transformedNode.name, transformedNode.desc,
                transformedNode.signature, transformedNode.exceptions?.toTypedArray()
        )
        val visitor = RemapVisitor(resultNode, remapper, nodeRemapper)
        try {
            transformedNode.accept(visitor)
        }
        catch (e: Throwable) {
            throw wrapException(e, transformedNode, "couldn't inline method call")
        }

        resultNode.visitLabel(end)

        if (inliningContext.isRoot) {
            val remapValue = remapper.remap(parameters.argsSizeOnStack + 1).value
            InternalFinallyBlockInliner.processInlineFunFinallyBlocks(
                    resultNode, lambdasFinallyBlocks, (remapValue as StackValue.Local).index
            )
        }

        processReturns(resultNode, labelOwner, remapReturn, end)
        //flush transformed node to output
        resultNode.accept(MethodBodyVisitor(adapter))

        sourceMapper.endMapping()
        return result
    }

    private fun doInline(node: MethodNode): MethodNode {
        val currentInvokes = LinkedList(invokeCalls)

        val resultNode = MethodNode(node.access, node.name, node.desc, node.signature, null)

        val iterator = transformations.iterator()

        val remapper = TypeRemapper.createFrom(currentTypeMapping)
        val remappingMethodAdapter = RemappingMethodAdapter(
                resultNode.access,
                resultNode.desc,
                resultNode,
                AsmTypeRemapper(remapper, result)
        )

        val markerShift = calcMarkerShift(parameters, node)
        val lambdaInliner = object : InlineAdapter(remappingMethodAdapter, parameters.argsSizeOnStack, sourceMapper) {
            private var transformationInfo: TransformationInfo? = null

            private fun handleAnonymousObjectRegeneration() {
                transformationInfo = iterator.next()

                val oldClassName = transformationInfo!!.oldClassName
                if (transformationInfo!!.shouldRegenerate(isSameModule)) {
                    //TODO: need poping of type but what to do with local funs???
                    val newClassName = transformationInfo!!.newClassName
                    remapper.addMapping(oldClassName, newClassName)

                    val childInliningContext = inliningContext.subInlineWithClassRegeneration(
                            inliningContext.nameGenerator,
                            currentTypeMapping,
                            inlineCallSiteInfo
                    )
                    val transformer = transformationInfo!!.createTransformer(childInliningContext, isSameModule)

                    val transformResult = transformer.doTransform(nodeRemapper)
                    result.merge(transformResult)
                    result.addChangedType(oldClassName, newClassName)

                    if (inliningContext.isInliningLambda && transformationInfo!!.canRemoveAfterTransformation()) {
                        // this class is transformed and original not used so we should remove original one after inlining
                        result.addClassToRemove(oldClassName)
                    }

                    if (transformResult.reifiedTypeParametersUsages.wereUsedReifiedParameters()) {
                        ReifiedTypeInliner.putNeedClassReificationMarker(mv)
                        result.reifiedTypeParametersUsages.mergeAll(transformResult.reifiedTypeParametersUsages)
                    }
                }
                else if (!transformationInfo!!.wasAlreadyRegenerated) {
                    result.addNotChangedClass(oldClassName)
                }
            }

            override fun anew(type: Type) {
                if (isAnonymousClass(type.internalName)) {
                    handleAnonymousObjectRegeneration()
                }

                //in case of regenerated transformationInfo type would be remapped to new one via remappingMethodAdapter
                super.anew(type)
            }

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
                if (/*INLINE_RUNTIME.equals(owner) &&*/ isInvokeOnLambda(owner, name)) { //TODO add method
                    assert(!currentInvokes.isEmpty())
                    val invokeCall = currentInvokes.remove()
                    val info = invokeCall.lambdaInfo

                    if (info == null) {
                        //noninlinable lambda
                        super.visitMethodInsn(opcode, owner, name, desc, itf)
                        return
                    }

                    val valueParamShift = Math.max(nextLocalIndex, markerShift)//NB: don't inline cause it changes
                    putStackValuesIntoLocals(listOf(*info.invokeMethod.argumentTypes), valueParamShift, this, desc)

                    if (invokeCall.lambdaInfo.invokeMethodDescriptor.valueParameters.isEmpty()) {
                        // There won't be no parameters processing and line call can be left without actual instructions.
                        // Note: if function is called on the line with other instructions like 1 + foo(), 'nop' will still be generated.
                        visitInsn(Opcodes.NOP)
                    }

                    addInlineMarker(this, true)
                    val lambdaParameters = info.addAllParameters(nodeRemapper)

                    val newCapturedRemapper = InlinedLambdaRemapper(
                            info.lambdaClassType.internalName, nodeRemapper, lambdaParameters,
                            info is DefaultLambda && info.isBoundCallableReference
                    )

                    setLambdaInlining(true)
                    val lambdaSMAP = info.node.classSMAP

                    val sourceMapper = if (inliningContext.classRegeneration && !inliningContext.isInliningLambda)
                        NestedSourceMapper(sourceMapper, lambdaSMAP.intervals, lambdaSMAP.sourceInfo)
                    else
                        InlineLambdaSourceMapper(sourceMapper.parent!!, info.node)
                    val inliner = MethodInliner(
                            info.node.node, lambdaParameters, inliningContext.subInlineLambda(info),
                            newCapturedRemapper, true /*cause all calls in same module as lambda*/,
                            "Lambda inlining " + info.lambdaClassType.internalName,
                            sourceMapper, inlineCallSiteInfo, null
                    )

                    val varRemapper = LocalVarRemapper(lambdaParameters, valueParamShift)
                    //TODO add skipped this and receiver
                    val lambdaResult = inliner.doInline(this.mv, varRemapper, true, info, invokeCall.finallyDepthShift)
                    result.mergeWithNotChangeInfo(lambdaResult)
                    result.reifiedTypeParametersUsages.mergeAll(lambdaResult.reifiedTypeParametersUsages)

                    //return value boxing/unboxing
                    val bridge = typeMapper.mapAsmMethod(ClosureCodegen.getErasedInvokeFunction(info.invokeMethodDescriptor))
                    StackValue.onStack(info.invokeMethod.returnType).put(bridge.returnType, this)
                    setLambdaInlining(false)
                    addInlineMarker(this, false)
                    sourceMapper.endMapping()
                    inlineOnlySmapSkipper?.markCallSiteLineNumber(remappingMethodAdapter)
                }
                else if (isAnonymousConstructorCall(owner, name)) { //TODO add method
                    //TODO add proper message
                    assert(transformationInfo is AnonymousObjectTransformationInfo) {
                        "<init> call doesn't correspond to object transformation info for '$owner.$name': $transformationInfo"
                    }
                    val parent = inliningContext.parent
                    val shouldRegenerate = transformationInfo!!.shouldRegenerate(isSameModule)
                    val isContinuation = parent != null && parent.isContinuation
                    if (shouldRegenerate || isContinuation) {
                        assert(shouldRegenerate || inlineCallSiteInfo.ownerClassName == transformationInfo!!.oldClassName) { "Only coroutines can call their own constructors" }

                        //put additional captured parameters on stack
                        var info = transformationInfo as AnonymousObjectTransformationInfo

                        val oldInfo = inliningContext.findAnonymousObjectTransformationInfo(owner)
                        if (oldInfo != null && isContinuation) {
                            info = oldInfo
                        }

                        for (capturedParamDesc in info.allRecapturedParameters) {
                            visitFieldInsn(
                                    Opcodes.GETSTATIC, capturedParamDesc.containingLambdaName,
                                    CAPTURED_FIELD_FOLD_PREFIX + capturedParamDesc.fieldName, capturedParamDesc.type.descriptor
                            )
                        }
                        super.visitMethodInsn(opcode, info.newClassName, name, info.newConstructorDescriptor, itf)

                        //TODO: add new inner class also for other contexts
                        if (inliningContext.parent is RegeneratedClassContext) {
                            inliningContext.parent.typeRemapper.addAdditionalMappings(
                                    transformationInfo!!.oldClassName, transformationInfo!!.newClassName
                            )
                        }

                        transformationInfo = null
                    }
                    else {
                        super.visitMethodInsn(opcode, owner, name, desc, itf)
                    }
                }
                else if ((!inliningContext.isInliningLambda || isDefaultLambdaWithReification(inliningContext.lambdaInfo!!)) &&
                         ReifiedTypeInliner.isNeedClassReificationMarker(MethodInsnNode(opcode, owner, name, desc, false))) {
                    //we shouldn't process here content of inlining lambda it should be reified at external level except default lambdas
                }
                else {
                    super.visitMethodInsn(opcode, owner, name, desc, itf)
                }
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
                if (opcode == Opcodes.GETSTATIC && (isAnonymousSingletonLoad(owner, name) || isWhenMappingAccess(owner, name))) {
                    handleAnonymousObjectRegeneration()
                }
                super.visitFieldInsn(opcode, owner, name, desc)
            }

            override fun visitMaxs(stack: Int, locals: Int) {
                lambdasFinallyBlocks = resultNode.tryCatchBlocks.size
                super.visitMaxs(stack, locals)
            }
        }

        node.accept(lambdaInliner)

        return resultNode
    }

    private fun isDefaultLambdaWithReification(lambdaInfo: LambdaInfo) =
            lambdaInfo is DefaultLambda && lambdaInfo.needReification

    private fun prepareNode(node: MethodNode, finallyDeepShift: Int): MethodNode {
        node.instructions.resetLabels()

        val capturedParamsSize = parameters.capturedParametersSizeOnStack
        val realParametersSize = parameters.realParametersSizeOnStack

        val transformedNode = object : MethodNode(
                API, node.access, node.name,
                Type.getMethodDescriptor(Type.getReturnType(node.desc), *(Type.getArgumentTypes(node.desc) + parameters.capturedTypes)),
                node.signature, node.exceptions?.toTypedArray()
        ) {
            private val GENERATE_DEBUG_INFO = GENERATE_SMAP && inlineOnlySmapSkipper == null

            private val isInliningLambda = nodeRemapper.isInsideInliningLambda

            private fun getNewIndex(`var`: Int): Int {
                return `var` + if (`var` < realParametersSize) 0 else capturedParamsSize
            }

            override fun visitVarInsn(opcode: Int, `var`: Int) {
                super.visitVarInsn(opcode, getNewIndex(`var`))
            }

            override fun visitIincInsn(`var`: Int, increment: Int) {
                super.visitIincInsn(getNewIndex(`var`), increment)
            }

            override fun visitMaxs(maxStack: Int, maxLocals: Int) {
                super.visitMaxs(maxStack, maxLocals + capturedParamsSize)
            }

            override fun visitLineNumber(line: Int, start: Label) {
                if (isInliningLambda || GENERATE_DEBUG_INFO) {
                    super.visitLineNumber(line, start)
                }
            }

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
                if (DEFAULT_LAMBDA_FAKE_CALL == owner) {
                    val index = name.substringAfter(DEFAULT_LAMBDA_FAKE_CALL).toInt()
                    val lambda = getLambdaIfExists(index) as DefaultLambda
                    lambda.parameterOffsetsInDefault.zip(lambda.capturedVars).asReversed().forEach {
                        (_, captured) ->
                        val originalBoundReceiverType = lambda.originalBoundReceiverType
                        if (lambda.isBoundCallableReference && AsmUtil.isPrimitive(originalBoundReceiverType)) {
                            StackValue.onStack(originalBoundReceiverType!!).put(captured.type, InstructionAdapter(this))
                        }
                        super.visitFieldInsn(
                                Opcodes.PUTSTATIC, captured.containingLambdaName, CAPTURED_FIELD_FOLD_PREFIX + captured.fieldName, captured.type.descriptor
                        )
                    }
                }
                else {
                    super.visitMethodInsn(opcode, owner, name, desc, itf)
                }
            }

            override fun visitLocalVariable(
                    name: String, desc: String, signature: String?, start: Label, end: Label, index: Int
            ) {
                if (isInliningLambda || GENERATE_DEBUG_INFO) {
                    val varSuffix = if (inliningContext.isRoot && !isFakeLocalVariableForInline(name)) INLINE_FUN_VAR_SUFFIX else ""
                    val varName = if (!varSuffix.isEmpty() && name == "this") name + "_" else name
                    super.visitLocalVariable(varName + varSuffix, desc, signature, start, end, getNewIndex(index))
                }
            }
        }

        node.accept(transformedNode)

        transformCaptured(transformedNode)
        transformFinallyDeepIndex(transformedNode, finallyDeepShift)

        return transformedNode
    }

    private fun markPlacesForInlineAndRemoveInlinable(
            node: MethodNode, labelOwner: LabelOwner, finallyDeepShift: Int
    ): MethodNode {
        val processingNode = prepareNode(node, finallyDeepShift)

        normalizeLocalReturns(processingNode, labelOwner)

        val sources = analyzeMethodNodeWithoutMandatoryTransformations(processingNode)

        val toDelete = SmartSet.create<AbstractInsnNode>()
        val instructions = processingNode.instructions

        var awaitClassReification = false
        var currentFinallyDeep = 0

        InsnSequence(instructions).forEach { cur ->
            val frame = sources[instructions.indexOf(cur)]

            if (frame != null) {
                if (ReifiedTypeInliner.isNeedClassReificationMarker(cur)) {
                    awaitClassReification = true
                }
                else if (cur is MethodInsnNode) {
                    if (isFinallyStart(cur)) {
                        //TODO deep index calc could be more precise
                        currentFinallyDeep = getConstant(cur.previous)
                    }

                    val owner = cur.owner
                    val name = cur.name
                    //TODO check closure
                    val argTypes = Type.getArgumentTypes(cur.desc)
                    val paramCount = argTypes.size + 1//non static
                    val firstParameterIndex = frame.stackSize - paramCount
                    if (isInvokeOnLambda(owner, name) /*&& methodInsnNode.owner.equals(INLINE_RUNTIME)*/) {
                        val sourceValue = frame.getStack(firstParameterIndex)
                        val lambdaInfo = getLambdaIfExistsAndMarkInstructions(sourceValue, true, instructions, sources, toDelete)
                        invokeCalls.add(InvokeCall(lambdaInfo, currentFinallyDeep))
                    }
                    else if (isAnonymousConstructorCall(owner, name)) {
                        val lambdaMapping = HashMap<Int, LambdaInfo>()

                        var offset = 0
                        var capturesAnonymousObjectThatMustBeRegenerated = false
                        for (i in 0..paramCount - 1) {
                            val sourceValue = frame.getStack(firstParameterIndex + i)
                            val lambdaInfo = getLambdaIfExistsAndMarkInstructions(sourceValue, false, instructions, sources, toDelete
                            )
                            if (lambdaInfo != null) {
                                lambdaMapping.put(offset, lambdaInfo)
                            }
                            else if (i < argTypes.size && isAnonymousClassThatMustBeRegenerated(argTypes[i])) {
                                capturesAnonymousObjectThatMustBeRegenerated = true
                            }

                            offset += if (i == 0) 1 else argTypes[i - 1].size
                        }

                        transformations.add(
                                buildConstructorInvocation(
                                        owner, cur.desc, lambdaMapping, awaitClassReification, capturesAnonymousObjectThatMustBeRegenerated
                                )
                        )
                        awaitClassReification = false
                    }
                    else if (inliningContext.isInliningLambda && ReifiedTypeInliner.isOperationReifiedMarker(cur)) {
                        val reificationArgument = cur.reificationArgument
                        val parameterName = reificationArgument!!.parameterName
                        result.reifiedTypeParametersUsages.addUsedReifiedParameter(parameterName)
                    }
                }
                else if (cur.opcode == Opcodes.GETSTATIC) {
                    val fieldInsnNode = cur as FieldInsnNode?
                    val className = fieldInsnNode!!.owner
                    if (isAnonymousSingletonLoad(className, fieldInsnNode.name)) {
                        transformations.add(
                                AnonymousObjectTransformationInfo(
                                        className, awaitClassReification, isAlreadyRegenerated(className), true,
                                        inliningContext.nameGenerator
                                )
                        )
                        awaitClassReification = false
                    }
                    else if (isWhenMappingAccess(className, fieldInsnNode.name)) {
                        transformations.add(
                                WhenMappingTransformationInfo(
                                        className, inliningContext.nameGenerator, isAlreadyRegenerated(className), fieldInsnNode
                                )
                        )
                    }
                }
                else if (cur.opcode == Opcodes.POP) {
                    getLambdaIfExistsAndMarkInstructions(frame.top()!!, true, instructions, sources, toDelete)?.let {
                        toDelete.add(cur)
                    }
                }
            }
            else {
                //given frame is <tt>null</tt> if and only if the corresponding instruction cannot be reached (dead code).
                //clean dead code otherwise there is problems in unreachable finally block, don't touch label it cause try/catch/finally problems
                if (cur.type == AbstractInsnNode.LABEL) {
                    //NB: Cause we generate exception table for default handler using gaps (see ExpressionCodegen.visitTryExpression)
                    //it may occurs that interval for default handler starts before catch start label, so this label seems as dead,
                    //but as result all this labels will be merged into one (see KT-5863)
                }
                else {
                    toDelete.add(cur)
                }
            }
        }

        processingNode.remove(toDelete)

        //clean dead try/catch blocks
        processingNode.tryCatchBlocks.removeIf { it.isMeaningless() }

        return processingNode
    }

    private fun normalizeLocalReturns(node: MethodNode, labelOwner: LabelOwner) {
        val frames = analyzeMethodNodeBeforeInline(node)

        val localReturnsNormalizer = LocalReturnsNormalizer()

        for ((index, insnNode) in node.instructions.toArray().withIndex()) {
            val frame = frames[index] ?: continue
            // Don't care about dead code, it will be eliminated

            if (!isReturnOpcode(insnNode.opcode)) continue

            var insertBeforeInsn = insnNode

            // TODO extract isLocalReturn / isNonLocalReturn, see processReturns
            val labelName = getMarkedReturnLabelOrNull(insnNode)
            if (labelName != null) {
                if (!labelOwner.isMyLabel(labelName)) continue
                insertBeforeInsn = insnNode.previous
            }

            localReturnsNormalizer.addLocalReturnToTransform(insnNode, insertBeforeInsn, frame)
        }

        localReturnsNormalizer.transform(node)
    }

    private fun isAnonymousClassThatMustBeRegenerated(type: Type?): Boolean {
        if (type == null || type.sort != Type.OBJECT) return false
        val info = inliningContext.findAnonymousObjectTransformationInfo(type.internalName)
        return info != null && info.shouldRegenerate(true)
    }

    private fun analyzeMethodNodeBeforeInline(node: MethodNode): Array<Frame<SourceValue>?> {
        try {
            FixStackWithLabelNormalizationMethodTransformer().transform("fake", node)
        }
        catch (e: Throwable) {
            throw wrapException(e, node, "couldn't inline method call")
        }

        return analyzeMethodNodeWithoutMandatoryTransformations(node)
    }

    private fun buildConstructorInvocation(
            anonymousType: String,
            desc: String,
            lambdaMapping: Map<Int, LambdaInfo>,
            needReification: Boolean,
            capturesAnonymousObjectThatMustBeRegenerated: Boolean
    ): AnonymousObjectTransformationInfo {
        val memoizeAnonymousObject = inliningContext.findAnonymousObjectTransformationInfo(anonymousType) == null

        val info = AnonymousObjectTransformationInfo(
                anonymousType, needReification, lambdaMapping,
                inliningContext.classRegeneration,
                isAlreadyRegenerated(anonymousType),
                desc,
                false,
                inliningContext.nameGenerator,
                capturesAnonymousObjectThatMustBeRegenerated
        )

        if (memoizeAnonymousObject) {
            inliningContext.root.internalNameToAnonymousObjectTransformationInfo.put(anonymousType, info)
        }
        return info
    }

    private fun isAlreadyRegenerated(owner: String): Boolean {
        return inliningContext.typeRemapper.hasNoAdditionalMapping(owner)
    }

    internal fun getLambdaIfExists(insnNode: AbstractInsnNode): LambdaInfo? {
        return when {
            insnNode.opcode == Opcodes.ALOAD ->
                getLambdaIfExists((insnNode as VarInsnNode).`var`)
            insnNode is FieldInsnNode && insnNode.name.startsWith(CAPTURED_FIELD_FOLD_PREFIX) ->
                findCapturedField(insnNode, nodeRemapper).lambda
            else ->
                null
        }
    }

    private fun getLambdaIfExists(varIndex: Int): LambdaInfo? {
        if (varIndex < parameters.argsSizeOnStack) {
            return parameters.getParameterByDeclarationSlot(varIndex).lambda
        }
        return null
    }

    private fun transformCaptured(node: MethodNode) {
        if (nodeRemapper.isRoot) {
            return
        }

        // Fold all captured variables access chains
        //          ALOAD 0
        //          [ALOAD this$0]*
        //          GETFIELD $captured
        //  to GETFIELD $$$$captured
        // On future decoding this field could be inlined or unfolded to another field access chain
        // (this chain could differ cause some of this$0 could be inlined)
        var cur: AbstractInsnNode? = node.instructions.first
        while (cur != null) {
            if (cur is VarInsnNode && cur.opcode == Opcodes.ALOAD) {
                val varIndex = cur.`var`
                if (varIndex == 0 || nodeRemapper.shouldProcessNonAload0FieldAccessChains()) {
                    val accessChain = getCapturedFieldAccessChain((cur as VarInsnNode?)!!)
                    val insnNode = nodeRemapper.foldFieldAccessChainIfNeeded(accessChain, node)
                    if (insnNode != null) {
                        cur = insnNode
                    }
                }
            }
            cur = cur.next
        }
    }

    private fun wrapException(originalException: Throwable, node: MethodNode, errorSuffix: String): RuntimeException {
        return if (originalException is InlineException) {
            InlineException("$errorPrefix: $errorSuffix", originalException)
        }
        else {
            InlineException("$errorPrefix: $errorSuffix\nCause: ${node.nodeText}", originalException)
        }
    }

    private class LocalReturnsNormalizer {
        private class LocalReturn(
                private val returnInsn: AbstractInsnNode,
                private val insertBeforeInsn: AbstractInsnNode,
                private val frame: Frame<SourceValue>
        ) {

            fun transform(insnList: InsnList, returnVariableIndex: Int) {
                val isReturnWithValue = returnInsn.opcode != Opcodes.RETURN

                val expectedStackSize = if (isReturnWithValue) 1 else 0
                val actualStackSize = frame.stackSize
                if (expectedStackSize == actualStackSize) return

                var stackSize = actualStackSize
                if (isReturnWithValue) {
                    val storeOpcode = Opcodes.ISTORE + returnInsn.opcode - Opcodes.IRETURN
                    insnList.insertBefore(insertBeforeInsn, VarInsnNode(storeOpcode, returnVariableIndex))
                    stackSize--
                }

                while (stackSize > 0) {
                    val stackElementSize = frame.getStack(stackSize - 1).getSize()
                    val popOpcode = if (stackElementSize == 1) Opcodes.POP else Opcodes.POP2
                    insnList.insertBefore(insertBeforeInsn, InsnNode(popOpcode))
                    stackSize--
                }

                if (isReturnWithValue) {
                    val loadOpcode = Opcodes.ILOAD + returnInsn.opcode - Opcodes.IRETURN
                    insnList.insertBefore(insertBeforeInsn, VarInsnNode(loadOpcode, returnVariableIndex))
                }
            }
        }

        private val localReturns = SmartList<LocalReturn>()

        private var returnVariableSize = 0
        private var returnOpcode = -1

        internal fun addLocalReturnToTransform(
                returnInsn: AbstractInsnNode,
                insertBeforeInsn: AbstractInsnNode,
                sourceValueFrame: Frame<SourceValue>
        ) {
            assert(isReturnOpcode(returnInsn.opcode)) { "return instruction expected" }
            assert(returnOpcode < 0 || returnOpcode == returnInsn.opcode) { "Return op should be " + Printer.OPCODES[returnOpcode] + ", got " + Printer.OPCODES[returnInsn.opcode] }
            returnOpcode = returnInsn.opcode

            localReturns.add(LocalReturn(returnInsn, insertBeforeInsn, sourceValueFrame))

            if (returnInsn.opcode != Opcodes.RETURN) {
                returnVariableSize = if (returnInsn.opcode == Opcodes.LRETURN || returnInsn.opcode == Opcodes.DRETURN) {
                    2
                }
                else {
                    1
                }
            }
        }

        fun transform(methodNode: MethodNode) {
            var returnVariableIndex = -1
            if (returnVariableSize > 0) {
                returnVariableIndex = methodNode.maxLocals
                methodNode.maxLocals += returnVariableSize
            }

            for (localReturn in localReturns) {
                localReturn.transform(methodNode.instructions, returnVariableIndex)
            }
        }
    }

    //Place to insert finally blocks from try blocks that wraps inline fun call
    class PointForExternalFinallyBlocks(
            @JvmField val beforeIns: AbstractInsnNode,
            @JvmField val returnType: Type,
            @JvmField val finallyIntervalEnd: LabelNode
    )

    companion object {

        @JvmStatic
        fun findCapturedField(node: FieldInsnNode, fieldRemapper: FieldRemapper): CapturedParamInfo {
            assert(node.name.startsWith(CAPTURED_FIELD_FOLD_PREFIX)) {
                "Captured field template should start with $CAPTURED_FIELD_FOLD_PREFIX prefix"
            }
            val fin = FieldInsnNode(node.opcode, node.owner, node.name.substring(3), node.desc)
            val field = fieldRemapper.findField(fin) ?: throw IllegalStateException(
                    "Couldn't find captured field ${node.owner}.${node.name} in ${fieldRemapper.originalLambdaInternalName}"
            )
            return field
        }

        private fun analyzeMethodNodeWithoutMandatoryTransformations(node: MethodNode): Array<Frame<SourceValue>?> {
            val analyzer = object : Analyzer<SourceValue>(SourceInterpreter()) {
                override fun newFrame(nLocals: Int, nStack: Int): Frame<SourceValue> {
                    return object : Frame<SourceValue>(nLocals, nStack) {
                        @Throws(AnalyzerException::class)
                        override fun execute(insn: AbstractInsnNode, interpreter: Interpreter<SourceValue>) {
                            // This can be a void non-local return from a non-void method; Frame#execute would throw and do nothing else.
                            if (insn.opcode == Opcodes.RETURN) return
                            super.execute(insn, interpreter)
                        }
                    }
                }
            }

            try {
                return analyzer.analyze("fake", node)
            }
            catch (e: AnalyzerException) {
                throw RuntimeException(e)
            }

        }

        //remove next template:
        //      aload x
        //      LDC paramName
        //      INTRINSICS_CLASS_NAME.checkParameterIsNotNull(...)
        private fun removeClosureAssertions(node: MethodNode) {
            val toDelete = arrayListOf<AbstractInsnNode>()
            InsnSequence(node.instructions).filterIsInstance<MethodInsnNode>().forEach {
                methodInsnNode ->
                if (methodInsnNode.name == "checkParameterIsNotNull" && methodInsnNode.owner == IntrinsicMethods.INTRINSICS_CLASS_NAME) {
                    val prev = methodInsnNode.previous
                    assert(Opcodes.LDC == prev?.opcode) { "'checkParameterIsNotNull' should go after LDC but $prev" }
                    val prevPev = methodInsnNode.previous.previous
                    assert(Opcodes.ALOAD == prevPev?.opcode) { "'checkParameterIsNotNull' should be invoked on local var, but $prev" }

                    toDelete.add(prevPev)
                    toDelete.add(prev)
                    toDelete.add(methodInsnNode)
                }
            }

            node.remove(toDelete)
        }

        private fun transformFinallyDeepIndex(node: MethodNode, finallyDeepShift: Int) {
            if (finallyDeepShift == 0) {
                return
            }

            var cur: AbstractInsnNode? = node.instructions.first
            while (cur != null) {
                if (cur is MethodInsnNode && isFinallyMarker(cur)) {
                    val constant = cur.previous
                    val curDeep = getConstant(constant)
                    node.instructions.insert(constant, LdcInsnNode(curDeep + finallyDeepShift))
                    node.instructions.remove(constant)
                }
                cur = cur.next
            }
        }

        private fun getCapturedFieldAccessChain(aload0: VarInsnNode): List<AbstractInsnNode> {
            val lambdaAccessChain = mutableListOf<AbstractInsnNode>(aload0).apply {
                addAll(InsnSequence(aload0.next, null).filter { it.isMeaningful }.takeWhile {
                    insnNode ->
                    insnNode is FieldInsnNode && "this$0" == insnNode.name
                }.toList())
            }

            return lambdaAccessChain.apply {
                last().getNextMeaningful().takeIf { insn -> insn is FieldInsnNode }?.also {
                    //captured field access
                    insn -> add(insn)
                }
            }
        }

        private fun putStackValuesIntoLocals(
                directOrder: List<Type>, shift: Int, iv: InstructionAdapter, descriptor: String
        ) {
            val actualParams = Type.getArgumentTypes(descriptor)
            assert(actualParams.size == directOrder.size) {
                "Number of expected and actual params should be equals, but ${actualParams.size} != ${directOrder.size}}!"
            }

            var currentShift = shift + directOrder.sumBy { it.size }

            directOrder.asReversed().forEachIndexed { index, type ->
                currentShift -= type.size
                val typeOnStack = actualParams[index]
                if (typeOnStack != type) {
                    StackValue.onStack(typeOnStack).put(type, iv)
                }
                iv.store(currentShift, type)
            }
        }

        //process local and global returns (local substituted with goto end-label global kept unchanged)
        @JvmStatic
        fun processReturns(
                node: MethodNode, labelOwner: LabelOwner, remapReturn: Boolean, endLabel: Label?
        ): List<PointForExternalFinallyBlocks> {
            if (!remapReturn) {
                return emptyList()
            }
            val result = ArrayList<PointForExternalFinallyBlocks>()
            val instructions = node.instructions
            var insnNode: AbstractInsnNode? = instructions.first
            while (insnNode != null) {
                if (isReturnOpcode(insnNode.opcode)) {
                    var isLocalReturn = true
                    val labelName = getMarkedReturnLabelOrNull(insnNode)

                    if (labelName != null) {
                        isLocalReturn = labelOwner.isMyLabel(labelName)
                        //remove global return flag
                        if (isLocalReturn) {
                            instructions.remove(insnNode.previous)
                        }
                    }

                    if (isLocalReturn && endLabel != null) {
                        val labelNode = endLabel.info as LabelNode
                        val jumpInsnNode = JumpInsnNode(Opcodes.GOTO, labelNode)
                        instructions.insert(insnNode, jumpInsnNode)
                        instructions.remove(insnNode)
                        insnNode = jumpInsnNode
                    }

                    //generate finally block before nonLocalReturn flag/return/goto
                    val label = LabelNode()
                    instructions.insert(insnNode, label)
                    result.add(PointForExternalFinallyBlocks(
                            getInstructionToInsertFinallyBefore(insnNode, isLocalReturn), getReturnType(insnNode.opcode), label
                    ))
                }
                insnNode = insnNode.next
            }
            return result
        }

        private fun getInstructionToInsertFinallyBefore(nonLocalReturnOrJump: AbstractInsnNode, isLocal: Boolean): AbstractInsnNode {
            return if (isLocal) nonLocalReturnOrJump else nonLocalReturnOrJump.previous
        }
    }
}
