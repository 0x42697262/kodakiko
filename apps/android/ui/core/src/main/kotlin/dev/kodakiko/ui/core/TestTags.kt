package dev.kodakiko.ui.core

object TestTags {
    const val PULL_TO_SYNC = "pullToSync"

    private const val SEPARATOR = "."

    fun listItem(base: String, position: Int): String = base + SEPARATOR + position

    fun calendarDay(localDate: String): String = Calendar.DAY + SEPARATOR + localDate

    object Admin {
        const val COPY_INVITE = "admin.copyInvite"
        const val CREATE_INVITE = "admin.createInvite"
        const val ERROR = "admin.error"
        const val INVITE_DAYS = "admin.inviteDays"
        const val INVITE_ROW = "admin.inviteRow"
        const val INVITE_SHEET = "admin.inviteSheet"
        const val INVITE_URL = "admin.inviteUrl"
        const val NEW_INVITE = "admin.newInvite"
        const val NOT_ADMIN = "admin.notAdmin"
        const val QUOTA = "admin.quota"
        const val REVOKE = "admin.revoke"
        const val ROLE = "admin.role"
        const val ROLE_ADMIN = "admin.role.admin"
        const val ROLE_MEMBER = "admin.role.member"
        const val SAVE = "admin.save"
        const val SCREEN = "admin.screen"
        const val SEGMENT = "admin.segment"
        const val STAT = "admin.stat"
        const val USER_ROW = "admin.userRow"
        const val USER_SHEET = "admin.userSheet"
        const val USE_DEFAULT = "admin.useDefault"
    }

    object Advanced {
        const val DEBUG = "advanced.debug"
        const val DEBUG_LOG = "advanced.debugLog"
        const val DEBUG_TEXT = "advanced.debugText"
        const val DIAGNOSTICS = "advanced.diagnostics"
        const val MESSAGE = "advanced.message"
        const val REBUILD = "advanced.rebuild"
        const val RESET = "advanced.reset"
        const val RESET_CONFIRM = "advanced.resetConfirm"
        const val RESET_SHEET = "advanced.resetSheet"
        const val SCREEN = "advanced.screen"
    }

    object DebugLog {
        const val CLEAR = "debugLog.clear"
        const val CLEAR_CONFIRM = "debugLog.clearConfirm"
        const val CLEAR_SHEET = "debugLog.clearSheet"
        const val COPY = "debugLog.copy"
        const val EMPTY = "debugLog.empty"
        const val LINE = "debugLog.line"
        const val LIST = "debugLog.list"
        const val MESSAGE = "debugLog.message"
        const val SCREEN = "debugLog.screen"
        const val SHARE = "debugLog.share"
        const val SWITCH = "debugLog.switch"
    }

    object App {
        const val LOADING = "app.loading"
        const val LOADING_SCREEN = "app.loading.screen"
        const val LOAD_FAILED = "app.loadFailed"
        const val LOAD_FAILED_CLASS = "app.loadFailedClass"
        const val LOAD_FAILED_SCREEN = "app.loadFailed.screen"
    }

    object Bulk {
        const val ADD_TAG = "bulk.addTag"
        const val CANCEL = "bulk.cancel"
        const val CONFIRM_ADD = "bulk.confirmAdd"
        const val CONFIRM_DELETE = "bulk.confirmDelete"
        const val CONFIRM_REMOVE = "bulk.confirmRemove"
        const val DELETE_PANEL = "bulk.deletePanel"
        const val DELETE_PERMANENTLY = "bulk.deletePermanently"
        const val FAVORITE = "bulk.favorite"
        const val MENU = "bulk.menu"
        const val REMOVE_FROM_JOURNAL = "bulk.removeFromJournal"
        const val REMOVE_TAG = "bulk.removeTag"
        const val RESTORE = "bulk.restore"
        const val RESULT = "bulk.result"
        const val SHARE = "bulk.share"
        const val TAG_OPTION = "bulk.tagOption"
        const val TAG_PANEL = "bulk.tagPanel"
        const val TRASH = "bulk.trash"
        const val UNFAVORITE = "bulk.unfavorite"
    }

    object Calendar {
        const val DAY = "calendar.day"
        const val DAY_NUMBER = "calendar.dayNumber"
        const val JUMP = "calendar.jump"
        const val JUMP_FIELD = "calendar.jumpField"
        const val JUMP_INVALID = "calendar.jumpInvalid"
        const val JUMP_MONTH = "calendar.jumpMonth"
        const val JUMP_NEXT_YEAR = "calendar.jumpNextYear"
        const val JUMP_PREVIOUS_YEAR = "calendar.jumpPreviousYear"
        const val JUMP_YEAR = "calendar.jumpYear"
        const val JUMP_SHEET = "calendar.jumpSheet"
        const val JUMP_SUBMIT = "calendar.jumpSubmit"
        const val MONTH_TITLE = "calendar.monthTitle"
        const val NEXT = "calendar.next"
        const val PAGER = "calendar.pager"
        const val PREVIOUS = "calendar.previous"
        const val SCREEN = "calendar.screen"
        const val TODAY = "calendar.today"
        const val WEEKDAY = "calendar.weekday"
    }

    object CancelDeletion {
        const val BODY = "cancelDeletion.body"
        const val SCREEN = "cancelDeletion.screen"
        const val SIGN_OUT = "cancelDeletion.signOut"
        const val SUBMIT = "cancelDeletion.submit"
        const val TITLE = "cancelDeletion.title"
    }

    object Capture {
        const val ADD_TO_ENTRY = "capture.addToEntry"
        const val ALLOW_CAMERA = "capture.allowCamera"
        const val CAMERA = "capture.camera"
        const val CAMERA_PERMISSION_BODY = "capture.cameraPermissionBody"
        const val CAMERA_UNAVAILABLE = "capture.cameraUnavailable"
        const val CANCEL_ADD = "capture.cancelAdd"
        const val CLOSE = "capture.close"
        const val DISCARD = "capture.discard"
        const val DISCARD_PANEL = "capture.discardPanel"
        const val FLIP = "capture.flip"
        const val IMPORTING = "capture.importing"
        const val KEEP = "capture.keep"
        const val MODE_PHOTO = "capture.mode.photo"
        const val MODE_VIDEO = "capture.mode.video"
        const val NEXT = "capture.next"
        const val OPEN_SETTINGS = "capture.openSettings"
        const val PICK_FROM_GALLERY = "capture.pickFromGallery"
        const val REFUSALS = "capture.refusals"
        const val REMOVE_ITEM = "capture.removeItem"
        const val SCREEN = "capture.screen"
        const val SHUTTER = "capture.shutter"
        const val SYSTEM_PHOTO = "capture.systemPhoto"
        const val SYSTEM_VIDEO = "capture.systemVideo"
        const val TIMER = "capture.timer"
        const val TRAY_ITEM = "capture.trayItem"
    }

    object Collection {
        const val DELETE = "collection.delete"
        const val DELETE_CONFIRM = "collection.deleteConfirm"
        const val LEAVE = "collection.leave"
        const val LEAVE_CONFIRM = "collection.leaveConfirm"
        const val MORE = "collection.more"
        const val RENAME = "collection.rename"
        const val SCREEN = "collection.screen"
        const val SHARE_WITH = "collection.shareWith"
        const val SHEET = "collection.sheet"
        const val SUBTITLE = "collection.subtitle"
        const val TITLE = "collection.title"
    }

    object Compose {
        const val BACK = "compose.back"
        const val CHANGE = "compose.change"
        const val FAILURE = "compose.failure"
        const val NOTE = "compose.note"
        const val REMOVE_ITEM = "compose.removeItem"
        const val SAVE = "compose.save"
        const val SCREEN = "compose.screen"
        const val WHEN_VALUE = "compose.whenValue"
    }

    object Connect {
        const val ACTION = "connect.action"
        const val BACK = "connect.back"
        const val DONE = "connect.done"
        const val INVITE = "connect.invite"
        const val PASSWORD = "connect.password"
        const val SCREEN = "connect.screen"
        const val SERVER = "connect.server"
        const val USERNAME = "connect.username"
    }

    object Crop {
        const val APPLY = "crop.apply"
        const val AREA = "crop.area"
        const val BOTTOM_LEFT = "crop.bottomLeft"
        const val BOTTOM_RIGHT = "crop.bottomRight"
        const val CANCEL = "crop.cancel"
        const val FAILED = "crop.failed"
        const val SHEET = "crop.sheet"
        const val TOP_LEFT = "crop.topLeft"
        const val TOP_RIGHT = "crop.topRight"
    }

    object Day {
        const val BACK = "day.back"
        const val EMPTY = "day.empty"
        const val SCREEN = "day.screen"
        const val TITLE = "day.title"
    }

    object Details {
        const val SCREEN = "details.screen"
    }

    object Diagnostics {
        const val LINE = "diagnostics.line"
        const val RUN_VECTORS = "diagnostics.runVectors"
        const val SCREEN = "diagnostics.screen"
    }

    object Edit {
        const val ADD_FROM_DEVICE = "edit.addFromDevice"
        const val ADD_NOTE = "edit.addNote"
        const val CAPTION = "edit.caption"
        const val CAPTION_FIELD = "edit.captionField"
        const val CAPTION_SAVE = "edit.captionSave"
        const val CAPTION_SHEET = "edit.captionSheet"
        const val CAPTION_TEXT = "edit.captionText"
        const val CREATING = "edit.creating"
        const val CREATING_SPINNER = "edit.creatingSpinner"
        const val CROP = "edit.crop"
        const val DOT = "edit.dot"
        const val DOTS = "edit.dots"
        const val EDIT_NOTE = "edit.editNote"
        const val FAILED = "edit.failed"
        const val FAVORITE = "edit.favorite"
        const val INLINE_VIDEO = "edit.inlineVideo"
        const val LOADING = "edit.loading"
        const val MEDIA_MENU = "edit.mediaMenu"
        const val MORE = "edit.more"
        const val NOTE_DONE = "edit.noteDone"
        const val PAGE = "edit.page"
        const val PAGER = "edit.pager"
        const val PASTE = "edit.paste"
        const val PLAY = "edit.play"
        const val RECORD_VIDEO = "edit.recordVideo"
        const val RECORD_VOICE = "edit.recordVoice"
        const val REMOVE_CONFIRM = "edit.removeConfirm"
        const val REMOVE_SHEET = "edit.removeSheet"
        const val SAVE_TO_DEVICE = "edit.saveToDevice"
        const val SCREEN = "edit.screen"
        const val SET_COVER = "edit.setCover"
        const val SHARE = "edit.share"
        const val SHARE_ITEM = "edit.shareItem"
        const val SHARE_SHEET = "edit.shareSheet"
        const val TAKE_PHOTO = "edit.takePhoto"
        const val TRASH = "edit.trash"
        const val VIEW = "edit.view"
    }

    object Entry {
        const val SAVED = "entry.saved"
    }

    object EntryView {
        const val BACK = "entryView.back"
        const val SCREEN = "entryView.screen"
        const val TITLE = "entryView.title"
    }

    object Errors {
        const val GENERIC = "errors.generic"
        const val INVALID_CREDENTIALS = "errors.invalidCredentials"
        const val INVITE_INVALID = "errors.inviteInvalid"
        const val KDF_UNAVAILABLE = "errors.kdfUnavailable"
        const val NOT_HTTPS = "errors.notHttps"
        const val NOT_KODAKIKO = "errors.notKodakiko"
        const val RATE_LIMITED = "errors.rateLimited"
        const val RECOVERY_WORDS_INVALID = "errors.recoveryWordsInvalid"
        const val UNREACHABLE = "errors.unreachable"
        const val UNSAFE_SERVER = "errors.unsafeServer"
        const val USERNAME_INVALID = "errors.usernameInvalid"
        const val USERNAME_TAKEN = "errors.usernameTaken"
        const val WRONG_PASSWORD = "errors.wrongPassword"
    }

    object Export {
        const val CANCEL = "export.cancel"
        const val ENCRYPT = "export.encrypt"
        const val MESSAGE = "export.message"
        const val PASSPHRASE = "export.passphrase"
        const val PASSPHRASE_CONFIRM = "export.passphraseConfirm"
        const val PROGRESS = "export.progress"
        const val SKIPPED = "export.skipped"
        const val START = "export.start"
        const val SUMMARY = "export.summary"
    }

    object FastScroller {
        const val THUMB = "fastScroller.thumb"
    }

    object Filter {
        const val APPLY = "filter.apply"
        const val FROM = "filter.from"
        const val INVALID_DATE = "filter.invalidDate"
        const val RESET = "filter.reset"
        const val SHEET = "filter.sheet"
        const val START_AFTER_END = "filter.startAfterEnd"
        const val TO = "filter.to"
    }

    object Gallery {
        const val ACTIONS = "gallery.actions"
        const val ACTIONS_SHEET = "gallery.actionsSheet"
        const val CANCEL_SELECTION = "gallery.cancelSelection"
        const val CAPTURE = "gallery.capture"
        const val CLEAR_ALL = "gallery.clearAll"
        const val CLEAR_FILTERS = "gallery.clearFilters"
        const val COUNT = "gallery.count"
        const val DAY_HEADER = "gallery.dayHeader"
        const val EMPTY = "gallery.empty"
        const val FILTER = "gallery.filter"
        const val FILTER_CHIP = "gallery.filterChip"
        const val FILTER_CHIP_REMOVE = "gallery.filterChipRemove"
        const val MORE = "gallery.more"
        const val NOTICE_GONE = "gallery.noticeGone"
        const val NOTICE_TRASHED = "gallery.noticeTrashed"
        const val NOTICE_UNDO = "gallery.noticeUndo"
        const val NO_MATCHES = "gallery.noMatches"
        const val OPENING = "gallery.opening"
        const val OPENING_BUSY = "gallery.opening.busy"
        const val RANDOM = "gallery.random"
        const val SCREEN = "gallery.screen"
        const val SEARCH = "gallery.search"
        const val SEARCH_FIELD = "gallery.searchField"
        const val SELECTED = "gallery.selected"
        const val SELECT_ALL = "gallery.selectAll"
        const val SELECT_INVERSE = "gallery.selectInverse"
        const val TAG_TAB = "gallery.tagTab"
        const val TAG_TAB_ALL = "gallery.tagTab.all"
        const val TAG_TAB_FAVORITES = "gallery.tagTab.favorites"
        const val THUMBNAIL_PLACEHOLDER = "gallery.thumbnailPlaceholder"
        const val TILE = "gallery.tile"
        const val TILE_CHECKBOX = "gallery.tileCheckbox"
        const val TILE_PENDING = "gallery.tilePending"
        const val TILE_UNREADABLE = "gallery.tileUnreadable"
        const val TITLE = "gallery.title"
        const val TRASH = "gallery.trash"
    }

    object Home {
        const val SIGNED_IN_AS = "home.signedInAs"
        const val SIGN_OUT = "home.signOut"
    }

    object Import {
        const val ERROR = "import.error"
        const val FAILED = "import.failed"
        const val PASSPHRASE = "import.passphrase"
        const val PROGRESS = "import.progress"
        const val SCREEN = "import.screen"
        const val START = "import.start"
        const val SUMMARY = "import.summary"
    }

    object Invite {
        const val SCREEN = "invite.screen"
        const val TITLE = "invite.title"
    }

    object Links {
        const val CANCEL = "links.cancel"
        const val COPIED = "links.copied"
        const val COPY = "links.copy"
        const val COPY_ROW = "links.copyRow"
        const val CREATE = "links.create"
        const val CREATE_SCREEN = "links.createScreen"
        const val DONE = "links.done"
        const val ENTRY_ALL = "links.entryAll"
        const val ENTRY_NONE = "links.entryNone"
        const val ERROR = "links.error"
        const val EXPIRY = "links.expiry"
        const val INCLUDE_NOTES = "links.includeNotes"
        const val LIST_EMPTY = "links.listEmpty"
        const val LIST_SCREEN = "links.listScreen"
        const val MEDIA_TOGGLE = "links.mediaToggle"
        const val OFF_HINT = "links.offHint"
        const val PROGRESS = "links.progress"
        const val REVOKE_CONFIRM = "links.revokeConfirm"
        const val REVOKE_ROW = "links.revokeRow"
        const val REVOKE_SHEET = "links.revokeSheet"
        const val ROW = "links.row"
        const val SELECT_ALL = "links.selectAll"
        const val SELECT_NONE = "links.selectNone"
        const val SHARE = "links.share"
        const val SHARE_ROW = "links.shareRow"
        const val TITLE = "links.title"
        const val URL = "links.url"
        const val WARNING = "links.warning"
    }

    object LocalSetup {
        const val CONFIRM = "localSetup.confirm"
        const val CREATE = "localSetup.create"
        const val PASSWORD = "localSetup.password"
        const val SCREEN = "localSetup.screen"
        const val TITLE = "localSetup.title"
        const val UNDERSTAND = "localSetup.understand"
        const val USERNAME = "localSetup.username"
        const val WARNING = "localSetup.warning"
    }

    object Location {
        const val COORDINATES = "location.coordinates"
        const val OPEN = "location.open"
        const val REMOVE = "location.remove"
        const val SOURCE = "location.source"
    }

    object Lock {
        const val CANCEL = "lock.cancel"
        const val FORGOT = "lock.forgot"
        const val LOCKED_AS = "lock.lockedAs"
        const val LOCK_NOW = "lock.lockNow"
        const val PASSWORD = "lock.password"
        const val SCREEN = "lock.screen"
        const val SIGN_OUT = "lock.signOut"
        const val SIGN_OUT_CONFIRM = "lock.signOutConfirm"
        const val SIGN_OUT_SHEET = "lock.signOutSheet"
        const val TITLE = "lock.title"
        const val UNLOCK_DEVICE = "lock.unlockDevice"
        const val UNLOCK_PASSWORD = "lock.unlockPassword"
        const val UNREADABLE = "lock.unreadable"
        const val UPDATED = "lock.updated"
        const val WRONG_PASSWORD = "lock.wrongPassword"
    }

    object Markdown {
        const val BLOCK = "markdown.block"
        const val CANCEL = "markdown.cancel"
        const val LINK = "markdown.link"
        const val LINK_SHEET = "markdown.linkSheet"
        const val LINK_URL = "markdown.linkUrl"
        const val OPEN_LINK = "markdown.openLink"
        const val VIEW = "markdown.view"
    }

    object More {
        const val ABOUT = "more.about"
        const val DATA_STORAGE = "more.dataStorage"
        const val HELP = "more.help"
        const val HIDE_SHARED = "more.hideShared"
        const val LOCK_NOW = "more.lockNow"
        const val PAUSE_SYNC = "more.pauseSync"
        const val SCREEN = "more.screen"
        const val SETTINGS = "more.settings"
        const val STATISTICS = "more.statistics"
        const val TAGS = "more.tags"
        const val TRASH = "more.trash"
    }

    object OnThisDay {
        const val CARD = "onThisDay.card"
        const val GROUP = "onThisDay.group"
        const val TILE = "onThisDay.tile"
        const val TITLE = "onThisDay.title"
    }

    object Password {
        const val EQUALS_USERNAME = "password.equalsUsername"
        const val MISMATCH = "password.mismatch"
        const val SINGLE_REPEATED_CHARACTER = "password.singleRepeatedCharacter"
        const val TOO_SHORT = "password.tooShort"
    }

    object Recover {
        const val CONFIRM = "recover.confirm"
        const val NEW_PASSWORD = "recover.newPassword"
        const val SCREEN = "recover.screen"
        const val SUBMIT = "recover.submit"
        const val TITLE = "recover.title"
        const val USERNAME = "recover.username"
        const val WORDS = "recover.words"
    }

    object RecoveryKey {
        const val BODY = "recoveryKey.body"
        const val CONFIRM_FIELD = "recoveryKey.confirmField"
        const val CONFIRM_LABEL = "recoveryKey.confirmLabel"
        const val CONFIRM_SUBMIT = "recoveryKey.confirmSubmit"
        const val CONFIRM_TITLE = "recoveryKey.confirmTitle"
        const val CONFIRM_WRONG = "recoveryKey.confirmWrong"
        const val DONE = "recoveryKey.done"
        const val SCREEN = "recoveryKey.screen"
        const val TITLE = "recoveryKey.title"
        const val WORD = "recoveryKey.word"
    }

    object Server {
        const val SCREEN = "server.screen"
    }

    object Settings {
        const val THEME_PRESET = "settings.themePreset"
        const val THEME_PRESETS = "settings.themePresets"
        const val ABOUT_SCREEN = "settings.aboutScreen"
        const val ACCOUNT_SCREEN = "settings.accountScreen"
        const val APPEARANCE_SCREEN = "settings.appearanceScreen"
        const val APP_VERSION = "settings.appVersion"
        const val AUTOPLAY = "settings.autoplay"
        const val AUTOPLAY_MOBILE = "settings.autoplayMobile"
        const val AUTO_LOCK = "settings.autoLock"
        const val BACK = "settings.back"
        const val BADGE = "settings.badge"
        const val BIOMETRIC = "settings.biometric"
        const val CACHE_CAP = "settings.cacheCap"
        const val CAMERA_APP = "settings.cameraApp"
        const val CANCEL = "settings.cancel"
        const val CAPTURE_SCREEN = "settings.captureScreen"
        const val CHANGE_SERVER = "settings.changeServer"
        const val CHANGE_SERVER_CONFIRM = "settings.changeServerConfirm"
        const val CHANGE_SERVER_SHEET = "settings.changeServerSheet"
        const val CLEAR_CONFIRM = "settings.clearConfirm"
        const val CLEAR_ORIGINALS = "settings.clearOriginals"
        const val CLEAR_SHEET = "settings.clearSheet"
        const val COLUMNS_LANDSCAPE = "settings.columnsLandscape"
        const val COLUMNS_PORTRAIT = "settings.columnsPortrait"
        const val CONFIRM_PASSWORD = "settings.confirmPassword"
        const val CONFIRM_TRASH = "settings.confirmTrash"
        const val CURRENT_PASSWORD = "settings.currentPassword"
        const val DATE_FORMAT = "settings.dateFormat"
        const val DEAD_ITEM = "settings.deadItem"
        const val DELETE_CONFIRM = "settings.deleteConfirm"
        const val DELETE_PASSWORD = "settings.deletePassword"
        const val DELETE_SCREEN = "settings.deleteScreen"
        const val DELETE_USERNAME = "settings.deleteUsername"
        const val DISPLAY_MODE = "settings.displayMode"
        const val DYNAMIC_COLOR = "settings.dynamicColor"
        const val ENCRYPT_THUMBNAILS = "settings.encryptThumbnails"
        const val ERROR = "settings.error"
        const val EXPORT_SCREEN = "settings.exportScreen"
        const val FAILED_COUNT = "settings.failedCount"
        const val FULL_SCREEN = "settings.fullScreen"
        const val GALLERY_SCREEN = "settings.galleryScreen"
        const val GROUP_BY_DAY = "settings.groupByDay"
        const val HAPTICS = "settings.haptics"
        const val HIDE_BARS_ON_SCROLL = "settings.hideBarsOnScroll"
        const val HIDE_NOTE_PREVIEWS = "settings.hideNotePreviews"
        const val HIDE_SHARED = "settings.hideShared"
        const val HOLD_MAX = "settings.holdMax"
        const val HOLD_TO_RECORD = "settings.holdToRecord"
        const val KEEP_SCREEN_ON = "settings.keepScreenOn"
        const val LARGE_THRESHOLD = "settings.largeThreshold"
        const val LAST_SYNCED = "settings.lastSynced"
        const val LICENSE = "settings.license"
        const val LICENSES_EMPTY = "settings.licensesEmpty"
        const val LOCAL_ONLY = "settings.localOnly"
        const val LOCATION = "settings.location"
        const val LOCATION_DENIED = "settings.locationDenied"
        const val MIHON_ATTRIBUTION = "settings.mihonAttribution"
        const val NEW_PASSWORD = "settings.newPassword"
        const val ON_THIS_DAY = "settings.onThisDay"
        const val PASSWORD_MISMATCH = "settings.passwordMismatch"
        const val PASSWORD_RESULT = "settings.passwordResult"
        const val PASSWORD_SCREEN = "settings.passwordScreen"
        const val PASSWORD_SECURING = "settings.passwordSecuring"
        const val PASSWORD_SUBMIT = "settings.passwordSubmit"
        const val PURE_BLACK = "settings.pureBlack"
        const val RECOVERY_DONE = "settings.recoveryDone"
        const val RECOVERY_PASSWORD = "settings.recoveryPassword"
        const val RECOVERY_SHEET = "settings.recoverySheet"
        const val RECOVERY_SHOW = "settings.recoveryShow"
        const val RECOVERY_WORD = "settings.recoveryWord"
        const val RECOVERY_WORDS = "settings.recoveryWords"
        const val RELATIVE_DATES = "settings.relativeDates"
        const val RESULT = "settings.result"
        const val RETRY_FAILED = "settings.retryFailed"
        const val ROW_ABOUT = "settings.row.about"
        const val ROW_ACCOUNT = "settings.row.account"
        const val ROW_ADMIN = "settings.row.admin"
        const val ROW_ADVANCED = "settings.row.advanced"
        const val ROW_APPEARANCE = "settings.row.appearance"
        const val ROW_CAPTURE = "settings.row.capture"
        const val ROW_CONNECT = "settings.row.connect"
        const val ROW_DELETE_ACCOUNT = "settings.row.deleteAccount"
        const val ROW_EXPORT = "settings.row.export"
        const val ROW_GALLERY = "settings.row.gallery"
        const val ROW_IMPORT = "settings.row.import"
        const val ROW_LINKS = "settings.row.links"
        const val ROW_LOCK_NOW = "settings.row.lockNow"
        const val ROW_PASSWORD = "settings.row.password"
        const val ROW_RECOVERY = "settings.row.recovery"
        const val ROW_SECURITY = "settings.row.security"
        const val ROW_SERVER = "settings.row.server"
        const val ROW_SIGN_OUT = "settings.row.signOut"
        const val ROW_STORAGE = "settings.row.storage"
        const val ROW_SYNC = "settings.row.sync"
        const val ROW_TAGS = "settings.row.tags"
        const val SCREEN = "settings.screen"
        const val SEARCH = "settings.search"
        const val SEARCH_FIELD = "settings.searchField"
        const val SECURE_SCREEN = "settings.secureScreen"
        const val SECURITY_CODE = "settings.securityCode"
        const val SECURITY_SCREEN = "settings.securityScreen"
        const val SERVER = "settings.server"
        const val SERVER_VERSION = "settings.serverVersion"
        const val SHOW_COUNTS = "settings.showCounts"
        const val SHOW_TAG_TABS = "settings.showTagTabs"
        const val SIGN_OUT_CONFIRM = "settings.signOutConfirm"
        const val SIGN_OUT_SHEET = "settings.signOutSheet"
        const val START_CAMERA = "settings.startCamera"
        const val START_SCREEN = "settings.startScreen"
        const val STORAGE_SCREEN = "settings.storageScreen"
        const val STORAGE_USE = "settings.storageUse"
        const val SYNC_CONNECT = "settings.syncConnect"
        const val SYNC_LOCAL_ONLY = "settings.syncLocalOnly"
        const val SYNC_NOW = "settings.syncNow"
        const val SYNC_SCREEN = "settings.syncScreen"
        const val TECHNICAL_DETAILS = "settings.technicalDetails"
        const val THEME = "settings.theme"
        const val THUMBNAIL_CONFIRM = "settings.thumbnailConfirm"
        const val THUMBNAIL_PROGRESS = "settings.thumbnailProgress"
        const val THUMBNAIL_SHEET = "settings.thumbnailSheet"
        const val TIME_FORMAT = "settings.timeFormat"
        const val USAGE_ORIGINALS = "settings.usageOriginals"
        const val USAGE_PENDING = "settings.usagePending"
        const val USAGE_PINNED = "settings.usagePinned"
        const val USAGE_THUMBNAILS = "settings.usageThumbnails"
        const val USERNAME = "settings.username"
        const val WAITING = "settings.waiting"
        const val WAITING_WIFI = "settings.waitingWifi"
        const val WIFI_ONLY = "settings.wifiOnly"
    }

    object Share {
        const val CANCEL = "share.cancel"
        const val CONFIRM = "share.confirm"
        const val FAILED = "share.failed"
        const val FIND = "share.find"
        const val ITEM = "share.item"
        const val LEAVES_ENCRYPTION = "share.leavesEncryption"
        const val PICKER = "share.picker"
        const val RECIPIENT_ROW = "share.recipientRow"
        const val REMOVE = "share.remove"
        const val SCREEN = "share.screen"
        const val SECURITY_CODE = "share.securityCode"
        const val SEND = "share.send"
        const val STOP_CONFIRM = "share.stopConfirm"
        const val TEXT = "share.text"
        const val TEXT_CAPTIONS = "share.text.captions"
        const val TEXT_CAPTIONS_AND_NOTE = "share.text.captionsAndNote"
        const val TEXT_NONE = "share.text.none"
        const val TOO_BIG = "share.tooBig"
        const val USERNAME = "share.username"
    }

    object ShareEntries {
        const val ADD_TO = "shareEntries.addTo"
        const val CREATE_LINK = "shareEntries.createLink"
        const val JOURNAL = "shareEntries.journal"
        const val NEW_JOURNAL = "shareEntries.newJournal"
        const val RESULT = "shareEntries.result"
        const val SHEET = "shareEntries.sheet"
    }

    object Shared {
        const val BACK = "shared.back"
        const val CANCEL = "shared.cancel"
        const val CREATE = "shared.create"
        const val EMPTY_MINE = "shared.emptyMine"
        const val EMPTY_WITH_ME = "shared.emptyWithMe"
        const val ERROR = "shared.error"
        const val FLAG_OFF = "shared.flagOff"
        const val MINE_ROW = "shared.mineRow"
        const val NAME_FIELD = "shared.nameField"
        const val NEW = "shared.new"
        const val NEW_SHEET = "shared.newSheet"
        const val SAVE = "shared.save"
        const val SCREEN = "shared.screen"
        const val SHARING_OFF_HINT = "shared.sharingOffHint"
        const val WITH_ME_ROW = "shared.withMeRow"
    }

    object Sheet {
        const val COLUMNS = "sheet.columns"
        const val FILTER = "sheet.filter"
        const val GROUP = "sheet.group"
        const val SORT = "sheet.sort"
        const val TAB = "sheet.tab"
        const val TAB_APPS = "sheet.tab.apps"
        const val TAB_KODAKIKO = "sheet.tab.kodakiko"
    }

    object SignIn {
        const val DELETION_SCHEDULED = "signIn.deletionScheduled"
        const val FORGOT = "signIn.forgot"
        const val PASSWORD = "signIn.password"
        const val SCREEN = "signIn.screen"
        const val SUBMIT = "signIn.submit"
        const val TITLE = "signIn.title"
        const val USERNAME = "signIn.username"
    }

    object SignUp {
        const val CONFIRM = "signUp.confirm"
        const val INVITE = "signUp.invite"
        const val PASSWORD = "signUp.password"
        const val SCREEN = "signUp.screen"
        const val SECURING = "signUp.securing"
        const val SECURING_DETAIL = "signUp.securingDetail"
        const val SECURING_SCREEN = "signUp.securing.screen"
        const val SUBMIT = "signUp.submit"
        const val TITLE = "signUp.title"
        const val USERNAME = "signUp.username"
    }

    object Stats {
        const val DEVICE_CARD = "stats.deviceCard"
        const val JOURNAL_CARD = "stats.journalCard"
        const val MEDIA_CARD = "stats.mediaCard"
        const val OVERVIEW = "stats.overview"
        const val PER_MONTH = "stats.perMonth"
        const val PER_YEAR = "stats.perYear"
        const val SCREEN = "stats.screen"
        const val SERVER_CARD = "stats.serverCard"
        const val SYNC_CARD = "stats.syncCard"
    }

    object Storage {
        const val DEVICE = "storage.device"
        const val LOCATION = "storage.location"
    }

    object Sync {
        const val ALLOW_NOTIFICATIONS = "sync.allowNotifications"
        const val DEAD = "sync.dead"
        const val OFFLINE = "sync.offline"
        const val RETRY = "sync.retry"
        const val WAITING_FOR_WIFI = "sync.waitingForWifi"
    }

    object Tabs {
        const val CALENDAR = "tabs.calendar"
        const val GALLERY = "tabs.gallery"
        const val MORE = "tabs.more"
        const val SHARED = "tabs.shared"
    }

    object Tags {
        const val ADD = "tags.add"
        const val CHIP = "tags.chip"
        const val CHOOSE = "tags.choose"
        const val CONFIRM_DELETE = "tags.confirmDelete"
        const val DELETE = "tags.delete"
        const val DONE = "tags.done"
        const val DOWN = "tags.down"
        const val DRAG = "tags.drag"
        const val EDIT_TAGS = "tags.editTags"
        const val NAME_FIELD = "tags.nameField"
        const val OPTION = "tags.option"
        const val PICKER = "tags.picker"
        const val REMOVE = "tags.remove"
        const val RENAME = "tags.rename"
        const val ROW = "tags.row"
        const val SAVE = "tags.save"
        const val SCREEN = "tags.screen"
        const val UP = "tags.up"
    }

    object TimeEditor {
        const val DATE = "timeEditor.date"
        const val DONE = "timeEditor.done"
        const val INVALID = "timeEditor.invalid"
        const val RESET = "timeEditor.reset"
        const val TIME = "timeEditor.time"
    }

    object Toolbar {
        const val BOLD = "toolbar.bold"
        const val BULLET = "toolbar.bullet"
        const val CHECKLIST = "toolbar.checklist"
        const val HEADING = "toolbar.heading"
        const val ITALIC = "toolbar.italic"
        const val LINK = "toolbar.link"
        const val QUOTE = "toolbar.quote"
        const val STRIKE = "toolbar.strike"
        const val TIME = "toolbar.time"
    }

    object Trash {
        const val BACK = "trash.back"
        const val EMPTY = "trash.empty"
        const val RETENTION = "trash.retention"
        const val SCREEN = "trash.screen"
        const val TITLE = "trash.title"
    }

    object Video {
        const val CLOSE = "video.close"
        const val PLAYER = "video.player"
        const val PREPARING = "video.preparing"
        const val PROBLEM = "video.problem"
        const val RETRY = "video.retry"
        const val SCREEN = "video.screen"
    }

    object Viewer {
        const val FULL_SCREEN_PHOTO = "viewer.fullScreenPhoto"
        const val ADD_MEDIA = "viewer.addMedia"
        const val BACK = "viewer.back"
        const val CAPTION = "viewer.caption"
        const val CONFIRM_SHEET = "viewer.confirmSheet"
        const val DELETE_PERMANENTLY = "viewer.deletePermanently"
        const val DETAILS_ACTION = "viewer.detailsAction"
        const val DOWNLOADING = "viewer.downloading"
        const val DOWNLOAD_FAILED = "viewer.downloadFailed"
        const val EDIT = "viewer.edit"
        const val FAVORITE = "viewer.favorite"
        const val INLINE_VIDEO = "viewer.inlineVideo"
        const val INLINE_VIDEO_MUTE_SUFFIX = ".mute"
        const val IN_TRASH = "viewer.inTrash"
        const val KEEP_OFFLINE = "viewer.keepOffline"
        const val MORE = "viewer.more"
        const val MORE_SHEET = "viewer.moreSheet"
        const val PAGE = "viewer.page"
        const val PAGE_INDICATOR = "viewer.pageIndicator"
        const val PLAY = "viewer.play"
        const val REMOVE_OFFLINE = "viewer.removeOffline"
        const val RESTORE = "viewer.restore"
        const val RETRY = "viewer.retry"
        const val SCREEN = "viewer.screen"
        const val SHARE = "viewer.share"
        const val SHARED_BY = "viewer.sharedBy"
        const val SHARE_ACTION = "viewer.shareAction"
        const val SHARING_OFF = "viewer.sharingOff"
        const val SHOWING_PREVIEW = "viewer.showingPreview"
        const val TAG_CHIP = "viewer.tagChip"
        const val TIME = "viewer.time"
        const val TRASH = "viewer.trash"
        const val UNREADABLE = "viewer.unreadable"
        const val ZOOMABLE = "viewer.zoomable"
    }

    object Voice {
        const val DURATION = "voice.duration"
        const val ELAPSED = "voice.elapsed"
        const val FAILED = "voice.failed"
        const val LIST = "voice.list"
        const val PLAY = "voice.play"
        const val RECORDING_TILE = "voice.recordingTile"
        const val REMOVE = "voice.remove"
        const val STOP = "voice.stop"
        const val TILE = "voice.tile"
    }

    object Welcome {
        const val CONTINUE = "welcome.continue"
        const val LOCAL = "welcome.local"
        const val SCREEN = "welcome.screen"
        const val SERVER_LABEL = "welcome.serverLabel"
        const val SUBTITLE = "welcome.subtitle"
        const val TITLE = "welcome.title"
    }
}
