package dev.kodakiko.data.media

import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Sink
import java.io.IOException

internal object NoWriteFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {
    override fun sink(file: Path, mustCreate: Boolean): Sink = refuse(file)

    override fun appendingSink(file: Path, mustExist: Boolean): Sink = refuse(file)

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle = refuse(file)

    override fun createDirectory(dir: Path, mustCreate: Boolean): Unit = refuse(dir)

    override fun atomicMove(source: Path, target: Path): Unit = refuse(target)

    override fun createSymlink(source: Path, target: Path): Unit = refuse(source)

    private fun refuse(path: Path): Nothing = throw IOException(MediaText.imageWriteRefused(path.toString()))
}
