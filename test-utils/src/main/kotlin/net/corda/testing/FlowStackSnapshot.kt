package net.corda.testing

import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.fibers.Instrumented
import co.paralleluniverse.fibers.Stack
import co.paralleluniverse.fibers.Suspendable
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import net.corda.core.flows.*
import net.corda.core.flows.FlowStackSnapshot.Frame
import net.corda.core.internal.FlowStateMachine
import net.corda.core.serialization.SerializeAsToken
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.reflect.full.isSubclassOf

/**
 * Singleton holding a reference to the [FlowStackSnapshotFactoryImpl].
 */
class FlowStackSnapshotFactoryProvider private constructor() {

    private object Holder {
        val INSTANCE = FlowStackSnapshotFactoryImpl()
    }

    companion object {
        val instance: FlowStackSnapshotFactory by lazy { Holder.INSTANCE }
    }
}

/**
 * Replaces the default implementation (i.e. one that throws [NotImplementedError] exception) of the flow stack
 * snapshot logic with the [FlowStackSnapshotFactoryImpl].
 */
fun initialiseFlowStackSnapshotFactory() {
    FlowStackSnapshotDefaults.FLOW_STACK_SNAPSHOT_FACTORY = FlowStackSnapshotFactoryProvider.instance
}

private class FlowStackSnapshotFactoryImpl : FlowStackSnapshotFactory {

    @Suspendable
    override fun getFlowStackSnapshot(flowClass: Class<*>): FlowStackSnapshot {
        var snapshot: FlowStackSnapshot? = null
        val stackTrace = Fiber.currentFiber().stackTrace
        Fiber.parkAndSerialize { fiber, _ ->
            snapshot = extractStackSnapshotFromFiber(fiber, stackTrace.toList(), flowClass)
            Fiber.unparkDeserialized(fiber, fiber.scheduler)
        }
        // This is because the dump itself is on the stack, which means it creates a loop in the object graph, we set
        // it to null to break the loop
        val temporarySnapshot = snapshot
        snapshot = null
        return temporarySnapshot!!
    }

    override fun persistAsJsonFile(flowClass: Class<*>, baseDir: Path, flowId: String) {
        val flowStackSnapshot = getFlowStackSnapshot(flowClass)
        val mapper = ObjectMapper()
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT)
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        val file = createFile(baseDir, flowId)
        file.bufferedWriter().use { out ->
            mapper.writeValue(out, filterOutStackDump(flowStackSnapshot))
        }
    }

    private fun extractStackSnapshotFromFiber(fiber: Fiber<*>, stackTrace: List<StackTraceElement>, flowClass: Class<*>): FlowStackSnapshot {
        val stack = getFiberStack(fiber)
        val objectStack = getObjectStack(stack).toList()
        val frameOffsets = getFrameOffsets(stack)
        val frameObjects = frameOffsets.map { (frameOffset, frameSize) ->
            objectStack.subList(frameOffset + 1, frameOffset + frameSize + 1)
        }
        val relevantStackTrace = removeFirstStackTraceElements(removeConstructorStackTraceElements(stackTrace))
        val stackTraceToAnnotation = relevantStackTrace.map {
            val element = StackTraceElement(it.className, it.methodName, it.fileName, it.lineNumber)
            element to getInstrumentedAnnotation(element)
        }

        val frameObjectsIterator = frameObjects.listIterator()
        val frames = stackTraceToAnnotation.reversed().map { (element, annotation) ->
            // If annotation is null then the case indicates that this is an entry point - i.e.
            // the net.corda.node.services.statemachine.FlowStateMachineImpl.run method
            if (frameObjectsIterator.hasNext() && (annotation == null || !annotation.methodOptimized)) {
                Frame(element, frameObjectsIterator.next())
            } else {
                Frame(element, listOf())
            }
        }
        return FlowStackSnapshot(flowClass = flowClass, stackFrames = frames)
    }

    private fun filterOutStackDump(flowStackSnapshot: FlowStackSnapshot): FlowStackSnapshot {
        val framesFilteredByStackTraceElement = flowStackSnapshot.stackFrames.filter {
            !Class.forName(it.stackTraceElement!!.className).kotlin.isSubclassOf(FlowStateMachine::class)
        }
        val framesFilteredByObjects = framesFilteredByStackTraceElement.map {
            Frame(it.stackTraceElement, it.stackObjects.map {
                if (it != null && (it is FlowLogic<*> || it is FlowStateMachine<*> || it is Fiber<*> || it is SerializeAsToken)) {
                    StackFrameDataToken(it::class.java.name)
                } else {
                    it
                }
            })
        }
        return FlowStackSnapshot(flowStackSnapshot.timestamp, flowStackSnapshot.flowClass, framesFilteredByObjects)
    }

    private fun createFile(baseDir: Path, flowId: String): File {
        var file: File
        val dir = File(baseDir.toFile(), "flowStackSnapshots/${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)}/$flowId/")
        dir.mkdirs()
        file = File(dir, "flowStackSnapshot.json")
        if (file.exists()) {
            var index = 0
            do {
                file = File(dir, "flowStackSnapshot-${index++}.json")
            } while (file.exists())
        }
        return file
    }

    private fun getInstrumentedAnnotation(element: StackTraceElement): Instrumented? {
        for (method in Class.forName(element.className).methods) {
            if (method.name == element.methodName && method.isAnnotationPresent(Instrumented::class.java)) {
                return method.getAnnotation(Instrumented::class.java)
            }
        }
        return null
    }

    private fun removeFirstStackTraceElements(stackTrace: List<StackTraceElement>): List<StackTraceElement> {
        if (stackTrace.isEmpty()) {
            return stackTrace
        } else {
            return stackTrace.subList(1, stackTrace.size)
        }
    }

    private fun removeConstructorStackTraceElements(stackTrace: List<StackTraceElement>): List<StackTraceElement> {
        val newStackTrace = ArrayList<StackTraceElement>()
        var previousElement: StackTraceElement? = null
        for (element in stackTrace) {
            if (element.methodName == previousElement?.methodName &&
                    element.className == previousElement?.className &&
                    element.fileName == previousElement?.fileName) {
                continue
            }
            newStackTrace.add(element)
            previousElement = element
        }
        return newStackTrace
    }
}

private inline fun <reified R, A> R.getField(name: String): A {
    val field = R::class.java.getDeclaredField(name)
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(this) as A
}

private fun getFiberStack(fiber: Fiber<*>): Stack {
    return fiber.getField("stack")
}

private fun getObjectStack(stack: Stack): Array<Any?> {
    return stack.getField("dataObject")
}

private fun getPrimitiveStack(stack: Stack): LongArray {
    return stack.getField("dataLong")
}

/*
 * Returns pairs of (offset, size of frame)
 */
private fun getFrameOffsets(stack: Stack): List<Pair<Int, Int>> {
    val primitiveStack = getPrimitiveStack(stack)
    val offsets = ArrayList<Pair<Int, Int>>()
    var offset = 0
    while (true) {
        val record = primitiveStack[offset]
        val slots = getNumSlots(record)
        if (slots > 0) {
            offsets.add(offset to slots)
            offset += slots + 1
        } else {
            break
        }
    }
    return offsets
}

private val MASK_FULL: Long = -1L

private fun getNumSlots(record: Long): Int {
    return getUnsignedBits(record, 14, 16).toInt()
}

private fun getUnsignedBits(word: Long, offset: Int, length: Int): Long {
    val a = 64 - length
    val b = a - offset
    return word.ushr(b) and MASK_FULL.ushr(a)
}
