package dev.kodakiko.media

class MediaRangeError(message: String) : Exception(message)

class UnsupportedVideoError(message: String) : Exception(message)

class RangeError(message: String) : IllegalArgumentException(message)

class TypeError(message: String) : IllegalArgumentException(message)
