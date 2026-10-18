package com.nikosm.voiceassistant.linux

/**
 * A child process for [ProcessCommunicationTest].
 *
 *  - `echo` writes one line to each stream and exits 0.
 *  - `flood` writes 256 KiB of stderr *before* closing stdout — far past a pipe buffer, and the
 *    shape a sequential drain deadlocks on.
 *  - `hang` opens stdout, writes a partial line, and never closes or exits — the shape the
 *    deadline exists for.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "echo" -> {
            System.out.print("out")
            System.err.print("err")
        }

        "flood" -> {
            System.err.write(ByteArray(256 * 1024) { 'e'.code.toByte() })
            System.err.flush()
            System.out.print("done")
        }

        "hang" -> {
            System.out.print("partial")
            System.out.flush()
            Thread.sleep(Long.MAX_VALUE)
        }

        else -> error("unknown mode ${args.firstOrNull()}")
    }
}
