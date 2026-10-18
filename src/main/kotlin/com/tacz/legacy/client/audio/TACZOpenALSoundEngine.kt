package com.tacz.legacy.client.audio

import com.jcraft.jogg.Packet
import com.jcraft.jogg.Page
import com.jcraft.jogg.StreamState
import com.jcraft.jogg.SyncState
import com.jcraft.jorbis.Block
import com.jcraft.jorbis.Comment
import com.jcraft.jorbis.DspState
import com.jcraft.jorbis.Info
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.client.sound.GunPackAssetLocator
import com.tacz.legacy.client.sound.TACZClientSoundHandle
import com.tacz.legacy.common.foundation.FocusedSmokeRuntime
import net.minecraft.entity.Entity
import net.minecraft.util.ResourceLocation
import org.apache.logging.log4j.Marker
import org.apache.logging.log4j.MarkerManager
import org.lwjgl.BufferUtils
import org.lwjgl.openal.AL10
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

internal object TACZOpenALSoundEngine {
    private const val MAX_CONCURRENT_SOURCES: Int = 24
    private const val OGG_READ_BUFFER_SIZE: Int = 8192
    private const val MAX_DECODE_BYTES: Int = 60 * 1024 * 1024

    private val MARKER: Marker = MarkerManager.getMarker("TACZOpenAL")

    private data class ReloadRequest(
        val packSources: List<File>,
        val descriptors: List<TACZAudioAssetDescriptor>,
    )

    internal data class PcmData(
        val data: ByteArray,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
    )

    private val soundBuffers = ConcurrentHashMap<ResourceLocation, Int>()
    private val activeSources = CopyOnWriteArrayList<Int>()

    @Volatile
    private var deferredReload: ReloadRequest? = null

    @Volatile
    private var openAlUnavailableLogged: Boolean = false

    @JvmStatic
    fun reload(packSources: List<File>, manifest: TACZAudioManifest) {
        clear()
        val descriptors = manifest.entries.values
            .asSequence()
            .filter { descriptor -> descriptor.exists && descriptor.assetLocation != null }
            .toList()

        if (descriptors.isEmpty()) {
            TACZLegacy.logger.info(MARKER, "OpenAL direct audio reload skipped: no pack-backed audio assets")
            return
        }

        if (!isOpenAlAvailable()) {
            deferredReload = ReloadRequest(ArrayList(packSources), descriptors)
            TACZLegacy.logger.info(MARKER, "OpenAL not ready; queued deferred gun-pack audio preload (count={})", descriptors.size)
            return
        }

        executeReload(packSources, descriptors)
    }

    @JvmStatic
    fun clear() {
        deferredReload = null
        stopAllActiveSources()
        if (isOpenAlAvailable()) {
            soundBuffers.values.forEach { bufferId ->
                runCatching { AL10.alDeleteBuffers(bufferId) }
            }
        }
        soundBuffers.clear()
    }

    @JvmStatic
    fun tick() {
        tryExecuteDeferredReloadIfNeeded()
        if (!isOpenAlAvailable()) {
            return
        }
        cleanupFinishedSources()
    }

    @JvmStatic
    fun play(
        entity: Entity?,
        soundId: ResourceLocation,
        volume: Float,
        pitch: Float,
        distance: Int,
    ): TACZClientSoundHandle? {
        tryExecuteDeferredReloadIfNeeded()
        if (!isOpenAlAvailable()) {
            return null
        }
        val bufferId = soundBuffers[soundId] ?: return null
        cleanupFinishedSources()
        trimOldestSourceIfNeeded()

        // 分配音源通道。OpenAL 通道耗尽时 alGenSources 会失败，
        // 这里先清理已播完/最老的声源再重试，确保当前枪声一定能响。
        val sourceId = allocateSourceGuaranteed()
        if (sourceId == 0) {
            TACZLegacy.logger.warn(MARKER, "OpenAL source allocation failed after cleanup for {}", soundId)
            return null
        }

        return runCatching {
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId)
            AL10.alSourcef(sourceId, AL10.AL_GAIN, volume.coerceIn(0f, 1f))
            AL10.alSourcef(sourceId, AL10.AL_PITCH, pitch.coerceIn(0.5f, 2f))

            if (entity == null || distance <= 0) {
                AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
                AL10.alSource3f(sourceId, AL10.AL_POSITION, 0f, 0f, 0f)
            } else {
                AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE)
                AL10.alSource3f(sourceId, AL10.AL_POSITION, entity.posX.toFloat(), entity.posY.toFloat(), entity.posZ.toFloat())
                AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 1f)
                AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, max(1f, distance / 8f))
                AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, max(1f, distance.toFloat()))
            }

            AL10.alSourcePlay(sourceId)
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                runCatching { AL10.alDeleteSources(sourceId) }
                return null
            }

            activeSources.add(sourceId)
            if (FocusedSmokeRuntime.enabled) {
                FocusedSmokeRuntime.markAudioPlaybackObserved("location=$soundId class=TACZOpenALSoundHandle")
            }
            TACZOpenALSoundHandle(soundId, sourceId)
        }.getOrElse {
            runCatching { AL10.alDeleteSources(sourceId) }
            null
        }
    }

    internal fun decodeOggToPcm(inputStream: InputStream): PcmData? {
        val syncState = SyncState()
        val streamState = StreamState()
        val page = Page()
        val packet = Packet()
        val info = Info()
        val comment = Comment()
        val dspState = DspState()
        val block = Block(dspState)
        val pcmOutput = ByteArrayOutputStream()

        try {
            syncState.init()
            info.init()
            comment.init()

            var headersParsed = false
            var packetCount = 0
            var eos = false

            outer@ while (!eos) {
                val bufferIndex = syncState.buffer(OGG_READ_BUFFER_SIZE)
                val buffer = syncState.data
                val bytesRead = inputStream.read(buffer, bufferIndex, OGG_READ_BUFFER_SIZE)
                if (bytesRead <= 0) {
                    syncState.wrote(0)
                    break
                }
                syncState.wrote(bytesRead)

                while (true) {
                    val pageResult = syncState.pageout(page)
                    if (pageResult == 0) break
                    if (pageResult < 0) continue

                    if (!headersParsed && packetCount == 0) {
                        streamState.init(page.serialno())
                    }
                    streamState.pagein(page)

                    while (true) {
                        val packetResult = streamState.packetout(packet)
                        if (packetResult == 0) break
                        if (packetResult < 0) continue

                        if (packetCount < 3) {
                            if (info.synthesis_headerin(comment, packet) < 0) {
                                return null
                            }
                            packetCount += 1
                            if (packetCount == 3) {
                                headersParsed = true
                                dspState.synthesis_init(info)
                                block.init(dspState)
                            }
                            continue
                        }

                        if (block.synthesis(packet) == 0) {
                            dspState.synthesis_blockin(block)
                        }

                        val pcmArray = Array<Array<FloatArray>>(1) { emptyArray() }
                        val index = IntArray(info.channels)

                        while (true) {
                            val samples = dspState.synthesis_pcmout(pcmArray, index)
                            if (samples <= 0) {
                                break
                            }

                            val channelData = pcmArray[0]
                            for (i in 0 until samples) {
                                for (ch in 0 until info.channels) {
                                    var sample = (channelData[ch][index[ch] + i] * 32767f).toInt()
                                    sample = sample.coerceIn(-32768, 32767)
                                    pcmOutput.write(sample and 0xFF)
                                    pcmOutput.write((sample shr 8) and 0xFF)
                                }
                            }
                            dspState.synthesis_read(samples)

                            if (pcmOutput.size() > MAX_DECODE_BYTES) {
                                TACZLegacy.logger.warn(MARKER, "Decode size limit exceeded; truncating current sound")
                                break@outer
                            }
                        }
                    }

                    if (page.eos() != 0) {
                        eos = true
                    }
                }
            }

            if (!headersParsed) {
                return null
            }
            val pcmBytes = pcmOutput.toByteArray()
            if (pcmBytes.isEmpty()) {
                return null
            }

            return PcmData(
                data = pcmBytes,
                channels = info.channels,
                sampleRate = info.rate,
                bitsPerSample = 16,
            )
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { block.clear() }
            runCatching { dspState.clear() }
            runCatching { info.clear() }
            runCatching { streamState.clear() }
            runCatching { syncState.clear() }
        }
    }

    internal fun stopSource(sourceId: Int) {
        if (isOpenAlAvailable()) {
            runCatching { AL10.alSourceStop(sourceId) }
            runCatching { AL10.alDeleteSources(sourceId) }
        }
        activeSources.remove(sourceId)
    }

    private fun tryExecuteDeferredReloadIfNeeded() {
        val request = deferredReload ?: return
        if (!isOpenAlAvailable()) {
            return
        }
        deferredReload = null
        executeReload(request.packSources, request.descriptors)
    }

    private fun executeReload(packSources: List<File>, descriptors: List<TACZAudioAssetDescriptor>) {
        var loaded = 0
        var failed = 0
        descriptors.forEach { descriptor ->
            val soundId = descriptor.soundId
            val assetLocation = descriptor.assetLocation ?: return@forEach
            if (soundBuffers.containsKey(soundId)) {
                return@forEach
            }
            val pcm = runCatching {
                GunPackAssetLocator.openResource(packSources, assetLocation)?.use(::decodeOggToPcm)
            }.getOrNull()
            if (pcm == null) {
                failed += 1
                return@forEach
            }
            val bufferId = createOpenAlBuffer(pcm)
            if (bufferId == null) {
                failed += 1
                return@forEach
            }
            soundBuffers[soundId] = bufferId
            loaded += 1
        }
        TACZLegacy.logger.info(
            MARKER,
            "OpenAL direct audio ready: loaded={} failed={} totalBuffers={}",
            loaded,
            failed,
            soundBuffers.size,
        )
    }

    private fun createOpenAlBuffer(pcm: PcmData): Int? {
        if (!isOpenAlAvailable()) {
            return null
        }
        val format = when (pcm.channels) {
            1 -> if (pcm.bitsPerSample == 8) AL10.AL_FORMAT_MONO8 else AL10.AL_FORMAT_MONO16
            2 -> if (pcm.bitsPerSample == 8) AL10.AL_FORMAT_STEREO8 else AL10.AL_FORMAT_STEREO16
            else -> return null
        }
        val byteBuffer: ByteBuffer = BufferUtils.createByteBuffer(pcm.data.size)
        byteBuffer.put(pcm.data)
        byteBuffer.flip()

        AL10.alGetError()
        val bufferId = AL10.alGenBuffers()
        AL10.alBufferData(bufferId, format, byteBuffer, pcm.sampleRate)
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            runCatching { AL10.alDeleteBuffers(bufferId) }
            return null
        }
        return bufferId
    }

    private fun trimOldestSourceIfNeeded() {
        if (activeSources.size < MAX_CONCURRENT_SOURCES) {
            return
        }
        val oldest = activeSources.firstOrNull() ?: return
        stopSource(oldest)
    }

    /**
     * 分配一个 OpenAL 音源通道。如果设备通道耗尽（alGenSources 报错），
     * 逐次强制释放自己管理的最老声源并重试，直到成功或确实无源可释放。
     * 保证当前最重要的枪声优先获得通道，而不是被静默丢弃。
     */
    private fun allocateSourceGuaranteed(): Int {
        while (true) {
            AL10.alGetError()
            val sourceId = AL10.alGenSources()
            if (AL10.alGetError() == AL10.AL_NO_ERROR) {
                return sourceId
            }
            if (sourceId != 0) {
                // 个别驱动会返回非 0 但同时设置错误标志，这里兜底防源泄漏
                runCatching { AL10.alDeleteSources(sourceId) }
            }
            // 先清掉已播放完的声源，再强制释放最老的一个
            cleanupFinishedSources()
            val oldest = activeSources.firstOrNull()
            if (oldest == null) {
                // 已没有自己管理的源可释放，做最后一次尝试
                AL10.alGetError()
                val lastChance = AL10.alGenSources()
                return if (AL10.alGetError() == AL10.AL_NO_ERROR) lastChance else 0
            }
            stopSource(oldest)
        }
    }

    private fun cleanupFinishedSources() {
        if (!isOpenAlAvailable()) {
            return
        }
        val finished = activeSources.filter { sourceId ->
            val state = runCatching { AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE) }.getOrDefault(AL10.AL_STOPPED)
            state != AL10.AL_PLAYING && state != AL10.AL_PAUSED
        }
        finished.forEach(::stopSource)
    }

    private fun stopAllActiveSources() {
        activeSources.toList().forEach(::stopSource)
        activeSources.clear()
    }

    private fun isOpenAlAvailable(): Boolean {
        return try {
            AL10.alGetError()
            openAlUnavailableLogged = false
            true
        } catch (_: UnsatisfiedLinkError) {
            logOpenAlUnavailableOnce()
            false
        } catch (_: NoClassDefFoundError) {
            logOpenAlUnavailableOnce()
            false
        } catch (_: IllegalStateException) {
            logOpenAlUnavailableOnce()
            false
        }
    }

    private fun logOpenAlUnavailableOnce() {
        if (openAlUnavailableLogged) {
            return
        }
        openAlUnavailableLogged = true
        TACZLegacy.logger.debug(MARKER, "OpenAL context not ready yet; direct gun-pack audio will retry later")
    }

    private class TACZOpenALSoundHandle(
        private val soundId: ResourceLocation,
        private val sourceId: Int,
    ) : TACZClientSoundHandle {
        override fun getSoundId(): ResourceLocation = soundId

        override fun stop() {
            stopSource(sourceId)
        }
    }
}
