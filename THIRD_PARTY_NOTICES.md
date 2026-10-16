# Third-party notices

PSA Lyric is licensed under AGPL-3.0-only.

## QQ Music QRC codec

`QrcLyricCodec.java` and `QrcDesCompat.java` implement the legacy QQ Music QRC decoding format.
They are derived from the implementation documented by the Lyrics-Companion project, whose source
identifies the underlying Proify/LyricProvider / tomakino/LyricProvider QRC implementation as
Apache-2.0-derived. The files retain their provenance comments.

Lyrics-Companion: https://github.com/zuo-qirun/Lyrics-Companion

## Reference-only behavior

The media-session selection behavior and lyric source ordering were studied against
Lyrics-Companion. The Apple-style lyric motion behavior was studied against md3Music.
PSA Lyric's Android/Kotlin rendering and application structure remain project-specific.
