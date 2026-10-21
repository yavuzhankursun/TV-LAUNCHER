package com.hizir.sadehome.airplay

/** Ağdan çözülen görüntü verisinin gideceği yer (Android tarafında MediaCodec). */
interface VideoSink {
    /** Yeni SPS/PPS (başlangıçta ve çözünürlük/yön değişince). [width]x[height]: kaynak görüntü boyutu. */
    fun onCodec(sps: ByteArray, pps: ByteArray, width: Int, height: Int)

    /**
     * Bir erişim birimi (Annex-B: 00 00 00 01 ile ayrılmış NAL'ler). [data] tamponu çağrıdan sonra yeniden
     * kullanılır; saklanacaksa kopyalanmalı. [sequenceHeader]: bu karenin başına SPS/PPS eklenmeli.
     */
    fun onFrame(data: ByteArray, length: Int, sequenceHeader: Boolean)

    /** Gönderen ekranı kilitledi/uyudu: görüntü geçici olarak durdu. */
    fun onPaused(paused: Boolean) {}
}

/** AirPlay ses akışı: ct = 2 (ALAC), 8 (AAC-ELD). */
interface AudioSink {
    fun start(ct: Int, samplesPerFrame: Int)

    /** Şifresi çözülmüş tek bir ses çerçevesi; [data] çağrıdan sonra yeniden kullanılır. */
    fun onPacket(seq: Int, data: ByteArray, length: Int)

    /** AirPlay ses düzeyi: -144 (sessiz) ile 0 dB arası. */
    fun setVolume(db: Float)
    fun flush()
    fun stop()
}

/** Sunucunun durumunu arayüze bildirir. Çağrılar ağ iş parçacığından gelir. */
interface AirPlayListener {
    fun onClient(name: String, model: String) {}
    fun onMirrorStart() {}
    fun onMirrorStop() {}
    fun onAudioStart() {}
    fun onAudioStop() {}
    /** Bağlantı tamamen bitti (TEARDOWN ya da kopma). */
    fun onSessionEnd() {}

    /**
     * Yeni bir gönderen akış kurmak istiyor: izin verilsin mi? Ağ iş parçacığından çağrılır ve cevap
     * gelene kadar bekleyebilir. [identity]: pair-verify'da imzayla doğrulanmış Ed25519 açık anahtarı
     * (hex); doğrulama yapılmadıysa null (hatırlanamaz, her seferinde sorulur). Varsayılan: reddet.
     */
    fun allowClient(identity: String?, name: String, model: String): Boolean = false
}
