package dev.kodakiko.data.db

class NewerSchemaError(val found: Long) : IllegalStateException(DbText.newerSchema(found))

class UnknownWireValueError(type: String, value: String) : IllegalStateException(DbText.unknownWireValue(type, value))
