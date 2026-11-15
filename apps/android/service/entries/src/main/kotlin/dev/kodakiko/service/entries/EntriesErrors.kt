package dev.kodakiko.service.entries

class AccountLockedException : IllegalStateException(EntriesText.ACCOUNT_LOCKED)

class EntryKeyUnavailableException : IllegalStateException(EntriesText.KEY_UNAVAILABLE)

class EmptyTrayException : IllegalStateException(EntriesText.EMPTY_TRAY)

class ReadOnlyEntryException : IllegalStateException(EntriesText.READ_ONLY)

class EntryNotFoundException : IllegalStateException(EntriesText.NOT_FOUND)

class TrayChangedException : IllegalStateException(EntriesText.TRAY_CHANGED)

class EntryChangedException : IllegalStateException(EntriesText.CHANGED_ELSEWHERE)

class HomeCollectionMissingException : IllegalStateException(EntriesText.HOME_MISSING)
