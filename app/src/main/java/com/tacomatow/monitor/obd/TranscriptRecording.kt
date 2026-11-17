package com.tacomatow.monitor.obd

/**
 * Collects the conversation so it can leave the phone as a file.
 *
 * The sink is separate from the wrapper on purpose: it outlives a session, so
 * a recording that spans a reconnect — or a bus reset — stays one file with
 * the whole story in it.
 *
 * It keeps a bounded number of entries. A polling session talks constantly and
 * a transcript that eats memory on a tow is worse than one that stops at four
 * thousand exchanges, which is far more than any diagnosis has needed.
 */
class TranscriptRecorder {
    companion object {
        const val MAX_ENTRIES = 4_000
    }

    private val lock = Any()
    private val entries = mutableListOf<Transcript.Entry>()

    @Volatile private var recording = false
    @Volatile private var truncated = false

    fun isRecording(): Boolean = recording

    fun wasTruncated(): Boolean = truncated

    fun start() {
        synchronized(lock) { entries.clear() }
        truncated = false
        recording = true
    }

    /** Stops recording and hands back what was captured. */
    fun stop(): List<Transcript.Entry> {
        recording = false
        return synchronized(lock) { entries.toList() }
    }

    fun record(command: String, timeoutMs: Int, reply: String?) {
        if (!recording) return
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                truncated = true
                return
            }
            entries += Transcript.Entry(command, timeoutMs, reply)
        }
    }
}

/**
 * Wraps a transport to record what went over it.
 *
 * A decorator rather than hooks in both transports: SPP and BLE then record
 * identically, and the recorder cannot drift from what the client actually
 * sent. Setup stage and the proven BLE channel are passed through, so wrapping
 * does not blind the error text or the remembered-channel optimisation.
 */
class RecordingTransport(
    private val delegate: ElmTransport,
    private val recorder: TranscriptRecorder,
) : ElmTransport {

    override fun open(): Boolean = delegate.open()

    override fun transact(cmd: String, timeoutMs: Int): String? =
        delegate.transact(cmd, timeoutMs).also { recorder.record(cmd, timeoutMs, it) }

    override fun close() = delegate.close()

    override fun transcript(): List<String> = delegate.transcript()

    override fun onLinkLost(handler: () -> Unit) = delegate.onLinkLost(handler)

    override fun setupStage(): String = delegate.setupStage()

    override fun confirmedChannel(): UartChannel? = delegate.confirmedChannel()
}
