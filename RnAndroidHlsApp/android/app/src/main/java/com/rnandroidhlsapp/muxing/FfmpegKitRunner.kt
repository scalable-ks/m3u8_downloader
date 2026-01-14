package com.rnandroidhlsapp.muxing

import android.util.Log

class FfmpegKitRunner : FfmpegRunner {
    override fun run(command: String): FfmpegResult {
        return try {
            // Load FFmpegKit class
            val ffmpegKitClass = try {
                Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            } catch (e: ClassNotFoundException) {
                Log.e("FfmpegKitRunner", "FFmpegKit library not found", e)
                return FfmpegResult(
                    success = false,
                    returnCode = -1,
                    output = "FFmpegKit library not found: ${e.message}",
                )
            }

            // Execute command
            val session = try {
                ffmpegKitClass.getMethod("execute", String::class.java).invoke(null, command)
            } catch (e: NoSuchMethodException) {
                Log.e("FfmpegKitRunner", "FFmpegKit.execute method not found", e)
                return FfmpegResult(
                    success = false,
                    returnCode = -1,
                    output = "FFmpegKit.execute method not found: ${e.message}",
                )
            } catch (e: Exception) {
                Log.e("FfmpegKitRunner", "Failed to execute FFmpeg command", e)
                return FfmpegResult(
                    success = false,
                    returnCode = -1,
                    output = "FFmpeg execution failed: ${e.message}",
                )
            }

            if (session == null) {
                Log.e("FfmpegKitRunner", "FFmpeg session is null")
                return FfmpegResult(
                    success = false,
                    returnCode = -1,
                    output = "FFmpeg session is null",
                )
            }

            // Get return code
            val returnCode = try {
                session.javaClass.getMethod("getReturnCode").invoke(session)
            } catch (e: NoSuchMethodException) {
                Log.e("FfmpegKitRunner", "getReturnCode method not found", e)
                return FfmpegResult(
                    success = false,
                    returnCode = -1,
                    output = "getReturnCode method not found: ${e.message}",
                )
            } catch (e: Exception) {
                Log.e("FfmpegKitRunner", "Failed to get return code", e)
                return FfmpegResult(
                    success = false,
                    returnCode = -1,
                    output = "Failed to get return code: ${e.message}",
                )
            }

            // Get output
            val output = try {
                val outputObj = session.javaClass.getMethod("getOutput").invoke(session)
                if (outputObj != null && outputObj !is String) {
                    Log.w("FfmpegKitRunner", "getOutput returned unexpected type: ${outputObj.javaClass}")
                    null
                } else {
                    outputObj as? String
                }
            } catch (e: NoSuchMethodException) {
                Log.e("FfmpegKitRunner", "getOutput method not found", e)
                null
            } catch (e: Exception) {
                Log.e("FfmpegKitRunner", "Failed to get output", e)
                null
            }

            // Load ReturnCode class
            val returnCodeClass = try {
                Class.forName("com.arthenica.ffmpegkit.ReturnCode")
            } catch (e: ClassNotFoundException) {
                Log.e("FfmpegKitRunner", "ReturnCode class not found", e)
                return FfmpegResult(
                    success = false,
                    returnCode = -1,
                    output = output ?: "ReturnCode class not found: ${e.message}",
                )
            }

            // Check if successful
            val isSuccess = try {
                val result = returnCodeClass.getMethod("isSuccess", returnCodeClass).invoke(null, returnCode)
                if (result !is Boolean) {
                    Log.w("FfmpegKitRunner", "isSuccess returned unexpected type: ${result?.javaClass}")
                    false
                } else {
                    result
                }
            } catch (e: NoSuchMethodException) {
                Log.e("FfmpegKitRunner", "isSuccess method not found", e)
                false
            } catch (e: Exception) {
                Log.e("FfmpegKitRunner", "Failed to check success status", e)
                false
            }

            // Get return code value
            val value = try {
                val valueObj = returnCode?.javaClass?.getMethod("getValue")?.invoke(returnCode)
                if (valueObj != null && valueObj !is Int) {
                    Log.w("FfmpegKitRunner", "getValue returned unexpected type: ${valueObj.javaClass}")
                    -1
                } else {
                    (valueObj as? Int) ?: -1
                }
            } catch (e: NoSuchMethodException) {
                Log.e("FfmpegKitRunner", "getValue method not found", e)
                -1
            } catch (e: Exception) {
                Log.e("FfmpegKitRunner", "Failed to get return code value", e)
                -1
            }

            FfmpegResult(
                success = isSuccess,
                returnCode = value,
                output = output,
            )
        } catch (e: Exception) {
            Log.e("FfmpegKitRunner", "Unexpected error running FFmpeg", e)
            FfmpegResult(
                success = false,
                returnCode = -1,
                output = "Unexpected error: ${e.message}",
            )
        }
    }
}
