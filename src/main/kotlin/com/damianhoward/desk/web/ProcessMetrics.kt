package com.damianhoward.desk.web

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.RuntimeMXBean
import java.lang.management.ThreadMXBean

/**
 * What this service can usefully publish at scrape frequency, which is not its readiness.
 *
 * `/readyz` GETs every upstream's `/healthz`. That is the right readiness signal — the desk is a
 * shell whose tabs are other services, so reaching them is the only thing worth proving — and it is
 * the wrong thing to run per scrape. A collector at fifteen seconds would multiply the estate's
 * health traffic by the number of tabs and make this service a load source on the ones it fronts.
 *
 * Nor does it publish a cached copy of the last verdict. A remembered boolean is a number that can
 * disagree with `/readyz`, which is the exact failure document 17's one-snapshot rule exists to
 * prevent, and the detector for that verdict already exists: UptimeRobot probes `/readyz` on its
 * own interval and alerts on the absence of `"ready":true`. Metrics here are for trend and
 * post-incident correlation.
 *
 * Heap against its ceiling is the load-bearing one. Both boxes are 1 GB with four JVMs, and the
 * ceilings were set by guess: this service was measured once, by hand, at 10 MB live against a
 * 96 MB ceiling. A series makes that a fact over time rather than one `jcmd` during an audit.
 *
 * This class is byte-identical to `risk-engine`'s but for its prefix and this paragraph. Two copies
 * is where the estate's rule leaves it — a third service needing it is the trigger to extract a
 * shared module, and `trading-system` and `orderbook` both render their own metrics from a
 * readiness snapshot instead, so that trigger is not met.
 */
class ProcessMetrics(
    private val runtime: RuntimeMXBean = ManagementFactory.getRuntimeMXBean(),
    private val memory: MemoryMXBean = ManagementFactory.getMemoryMXBean(),
    private val threads: ThreadMXBean = ManagementFactory.getThreadMXBean(),
    private val collectors: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans(),
) {
    fun render(): String {
        val out = StringBuilder()

        fun emit(
            name: String,
            help: String,
            type: String,
            samples: List<Pair<String, Any>>,
        ) {
            if (samples.isEmpty()) return
            out
                .append("# HELP ")
                .append(name)
                .append(' ')
                .append(help)
                .append('\n')
            out
                .append("# TYPE ")
                .append(name)
                .append(' ')
                .append(type)
                .append('\n')
            for ((labels, value) in samples) {
                out
                    .append(name)
                    .append(labels)
                    .append(' ')
                    .append(value)
                    .append('\n')
            }
        }

        fun gauge(
            name: String,
            help: String,
            value: Any,
        ) = emit(name, help, "gauge", listOf("" to value))

        gauge(
            "${PREFIX}process_uptime_seconds",
            "Seconds since this process started. A reset is a restart, wanted or not.",
            seconds(runtime.uptime),
        )

        val heap = memory.heapMemoryUsage
        gauge("${PREFIX}jvm_heap_used_bytes", "Heap in use after the last collection this reading saw.", heap.used)
        gauge("${PREFIX}jvm_heap_committed_bytes", "Heap the JVM currently holds from the operating system.", heap.committed)
        // -1 means no ceiling was configured. Publishing it would read as a real limit of minus one
        // byte, and every "used against max" expression built on it would be nonsense.
        if (heap.max >= 0) {
            gauge(
                "${PREFIX}jvm_heap_max_bytes",
                "The configured heap ceiling. Used against this is what the ceilings were guessed at.",
                heap.max,
            )
        }

        gauge("${PREFIX}jvm_threads", "Live threads, daemon and non-daemon.", threads.threadCount)

        // The collector names come from the JVM's own configuration, so the label set is fixed at
        // startup — bounded by configuration rather than by data, which is the rule that keeps a
        // series from being minted per observation.
        emit(
            "${PREFIX}jvm_gc_collections_total",
            "Collections each garbage collector has completed.",
            "counter",
            collectors.map { """{gc=${quote(it.name)}}""" to it.collectionCount },
        )
        emit(
            "${PREFIX}jvm_gc_seconds_total",
            "Seconds each garbage collector has spent collecting.",
            "counter",
            collectors.map { """{gc=${quote(it.name)}}""" to seconds(it.collectionTime) },
        )

        return out.toString()
    }

    // Rendered rather than divided into a Double, so a duration never reaches the endpoint in
    // exponential notation — Prometheus accepts it, humans reading a curl do not.
    private fun seconds(millis: Long): String = "%d.%03d".format(millis / 1000, millis % 1000)

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val PREFIX = "trading_desk_"

        /**
         * The Prometheus exposition content type. The version parameter is not decoration: a
         * collector reads it to decide which parser to use.
         */
        const val CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"
    }
}
