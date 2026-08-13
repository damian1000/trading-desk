package com.damianhoward.desk.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.MemoryUsage
import java.lang.management.RuntimeMXBean
import java.lang.management.ThreadMXBean
import javax.management.ObjectName

class ProcessMetricsTest {
    private fun metrics(
        uptimeMillis: Long = 90_000,
        heapUsed: Long = 10L * 1024 * 1024,
        heapCommitted: Long = 32L * 1024 * 1024,
        heapMax: Long = 96L * 1024 * 1024,
        threadCount: Int = 21,
        collectors: List<GarbageCollectorMXBean> = listOf(FakeCollector("G1 Young Generation", 12, 340)),
    ) = ProcessMetrics(
        runtime = FakeRuntime(uptimeMillis),
        memory = FakeMemory(MemoryUsage(0, heapUsed, heapCommitted, heapMax)),
        threads = FakeThreads(threadCount),
        collectors = collectors,
    ).render()

    @Test
    fun `heap is published against its ceiling`() {
        // The pair this exists for. One manual jcmd said 10 MB live against a 96 MB ceiling; a
        // series is what turns that into evidence for sizing rather than one reading during an audit.
        val body = metrics()
        assertTrue(body.contains("trading_desk_jvm_heap_used_bytes ${10L * 1024 * 1024}"), body)
        assertTrue(body.contains("trading_desk_jvm_heap_max_bytes ${96L * 1024 * 1024}"), body)
    }

    @Test
    fun `an unset heap ceiling is omitted rather than published as minus one`() {
        // getMax returns -1 when no ceiling is configured. Published, it reads as a real limit of
        // minus one byte, and every "used against max" expression built on it is nonsense.
        val body = metrics(heapMax = -1)
        assertFalse(body.contains("trading_desk_jvm_heap_max_bytes"), body)
        assertTrue(body.contains("trading_desk_jvm_heap_used_bytes"), body)
    }

    @Test
    fun `uptime and durations are published in seconds`() {
        val body = metrics(uptimeMillis = 90_500)
        assertTrue(body.contains("trading_desk_process_uptime_seconds 90.500"), body)
        assertTrue(body.contains("""trading_desk_jvm_gc_seconds_total{gc="G1 Young Generation"} 0.340"""), body)
    }

    @Test
    fun `each collector is labelled by name`() {
        val body =
            metrics(
                collectors =
                    listOf(
                        FakeCollector("G1 Young Generation", 12, 340),
                        FakeCollector("G1 Old Generation", 1, 90),
                    ),
            )
        assertTrue(body.contains("""trading_desk_jvm_gc_collections_total{gc="G1 Young Generation"} 12"""), body)
        assertTrue(body.contains("""trading_desk_jvm_gc_collections_total{gc="G1 Old Generation"} 1"""), body)
    }

    @Test
    fun `no upstream is probed`() {
        // The load-bearing property. /readyz GETs every upstream's /healthz, which is right per
        // probe and wrong per scrape: a collector at fifteen seconds would make this service a load
        // source on the ones it fronts. This class is given no gateway and no client, and that is
        // the guarantee rather than a convention.
        val body = metrics()
        assertFalse(body.contains("ready"), body)
        assertFalse(body.contains("upstream"), body)
    }

    @Test
    fun `every published series carries its HELP and TYPE`() {
        // A series without a TYPE is parsed as untyped, which silently costs rate() and increase().
        val body = metrics()
        val names =
            body
                .lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.substringBefore(' ').substringBefore('{') }
                .distinct()
                .toList()
        assertTrue(names.isNotEmpty(), body)
        for (name in names) {
            assertTrue(body.contains("# HELP $name "), "$name has no HELP\n$body")
            assertTrue(body.contains("# TYPE $name "), "$name has no TYPE\n$body")
        }
    }

    @Test
    fun `counters carry the suffix every dashboard assumes`() {
        val body = metrics()
        for (line in body.lineSequence().filter { it.startsWith("# TYPE ") }) {
            val (name, type) = line.removePrefix("# TYPE ").split(' ')
            if (type == "counter") assertTrue(name.endsWith("_total"), "$name is a counter without _total")
        }
    }

    @Test
    fun `the real beans render without throwing`() {
        // The fakes prove the shape; this proves the wiring, since a JVM bean returning something
        // unexpected would otherwise only surface on the box.
        val body = ProcessMetrics().render()
        assertTrue(body.contains("trading_desk_jvm_threads"), body)
        assertEquals("text/plain; version=0.0.4; charset=utf-8", ProcessMetrics.CONTENT_TYPE)
    }

    private class FakeRuntime(
        private val uptimeMillis: Long,
    ) : RuntimeMXBean by ManagementFactory.getRuntimeMXBean() {
        override fun getUptime(): Long = uptimeMillis
    }

    private class FakeMemory(
        private val heap: MemoryUsage,
    ) : MemoryMXBean by ManagementFactory.getMemoryMXBean() {
        override fun getHeapMemoryUsage(): MemoryUsage = heap
    }

    private class FakeThreads(
        private val count: Int,
    ) : ThreadMXBean by ManagementFactory.getThreadMXBean() {
        override fun getThreadCount(): Int = count
    }

    private class FakeCollector(
        private val collectorName: String,
        private val count: Long,
        private val timeMillis: Long,
    ) : GarbageCollectorMXBean {
        override fun getCollectionCount(): Long = count

        override fun getCollectionTime(): Long = timeMillis

        override fun getName(): String = collectorName

        override fun getMemoryPoolNames(): Array<String> = emptyArray()

        override fun isValid(): Boolean = true

        override fun getObjectName(): ObjectName = ObjectName("test:type=GarbageCollector,name=$collectorName")
    }
}
