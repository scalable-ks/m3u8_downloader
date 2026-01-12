package com.rnandroidhlsapp.muxing

class FfmpegKitRunner : FfmpegRunner {
    override fun run(command: String): FfmpegResult {
        val ffmpegKitClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
        val session = ffmpegKitClass.getMethod("execute", String::class.java).invoke(null, command)
        val returnCode = session.javaClass.getMethod("getReturnCode").invoke(session)
        val output = session.javaClass.getMethod("getOutput").invoke(session) as? String
        val returnCodeClass = Class.forName("com.arthenica.ffmpegkit.ReturnCode")
        val isSuccess =
            returnCodeClass.getMethod("isSuccess", returnCodeClass).invoke(null, returnCode) as Boolean
        val value = returnCode?.javaClass?.getMethod("getValue")?.invoke(returnCode) as? Int ?: -1
        return FfmpegResult(
            success = isSuccess,
            returnCode = value,
            output = output,
        )
    }
}
