# Shared journals and share to apps

Two ways to share: a **shared journal** that other accounts on the same server can read, and
**share to apps**, which hands decrypted files to another app. Public links are in
[public-links.md](public-links.md). Code: `:service:sharing` (journals, recipients, sealed share
payloads, share picker model), `:service:shareout` (decrypt to the share cache, release),
`:ui:feature:sharing` (Shared tab, journal, Share With, the Share sheet), `:platform:picker`
(Android share chooser through the FileProvider), `:app` (`sharing/SharingAdapters.kt`,
`di/EditBindings.kt` for the share-out wiring), and on the server
`apps/server/src/services/sharing-service.ts`.

## The Share sheet

Opened from the viewer's action row and ⋮, the edit page, and the gallery's bulk actions. It has
two tabs; **In Kodakiko** comes first when the server has sharing or public links turned on and
the account is not local-only, otherwise only **To apps** shows.

- **Drawn over the screen it came from** (v0.4.1): the sheet slides up over the viewer, edit page
  or gallery, which stays visible behind it. v0.4.0 drew it on a black page.
- **One entry, To apps only** (v0.4.1): when only To apps is possible (a local-only journal, or a
  server with sharing and public links both off) and one entry is shared, the sheet is skipped and
  the Android share chooser opens directly with every photo and video of the entry and no text. If
  that fails, the sheet shows with "Couldn't share these files.".

## Shared journals

A shared journal is a named collection that the owner fills with entries and shares, read-only,
with other accounts on the server (server flag `sharing`). Sharing actions need the network ("You're
offline. Sharing needs a connection.").

**How the keys work:** creating a journal makes a random collection key, wrapped by the owner's
master key; its name is sealed with that key. Adding an entry wraps the entry's key with the
collection key, so the entry keeps one copy of its data with two key wraps. Sharing seals the
collection key to the recipient's X25519 public key. The server sees memberships, sealed keys and
ciphertext only.

**Security code:** the recipient's public-key fingerprint, shown in large monospace before
sharing: "Ask (username) to open Settings › Account and read you their security code. If it
matches, nobody else can read what you share." Comparing is optional; it guards against a server
that swaps keys.

**Screens:**

- **Shared tab:** "My Shared Journals" (name and number of entries) with
  **New Shared Journal…** (name 1–100 characters), and "Shared With Me" (name, "from (owner)"). Empty
  states: "You haven't shared anything yet." / "Nothing has been shared with you yet." The tab is
  in the bottom bar only when the server has sharing on and the account is not local-only (the
  page itself says "Sharing is turned off on this server." or "Connect a server to share
  journals." if reached otherwise).
- **A journal** (`/collections/<id>`) shows its entries in the gallery grid. The owner's ⋮: Share
  With…, Rename…, Delete Journal… ("The entries stay in your journal. People you shared it with lose
  access."); selection offers Remove from This Journal. A recipient sees "Shared by (owner)" and
  ⋮ Leave… ("You'll lose access unless (owner) shares it again.").
- **Share With…** (`/collections/<id>/share`): find a username, compare the security code, Share;
  below, the people it is shared with, each with Remove ("They may have kept copies of what they
  already saw.").
- **In Kodakiko tab:** Add to Shared Journal… (pick one or create one) and Create Public Link….
  Entries not uploaded yet are reported ("not synced yet — try again shortly").

**For recipients:** shared entries sync like own ones (role `viewer`), appear in the gallery
(unless **Hide shared journals**), in their journal's view and in the Shared filter, and open in a
read-only viewer: no edit, trash or delete. Their tags are not added to the recipient's tag list.
Trashed entries are never sent to viewers. Leaving, being removed or a deleted journal removes the
journal and any entries left in no collection from the device at the next sync.

**Server rules:** up to 50 people per journal and 200 shared journals per user (settings
`MAX_RECIPIENTS_PER_COLLECTION`, `MAX_SHARED_COLLECTIONS_PER_USER`). Only the owner adds entries
(own, not trashed), shares, renames or deletes; the owner or the recipient can end a share. The
user's home journal cannot be deleted. Repeating an identical request returns the existing result;
a different one with the same id is a conflict. With the flag off every route answers
`FEATURE_DISABLED`.

## Share to apps

The **To apps** tab decrypts chosen items and gives them to another app (messages, mail, files):

- A note at the top: "Shared files leave Kodakiko unencrypted. The receiving app can read them."
- A picker grid of every item of the chosen entries, all checked; tap to uncheck. With a single
  item the grid is hidden (v0.4.1). The button shows the count and total size ("Share 3 items ·
  24 MB"); above 500 MB it warns "That is (size). Decrypting and sharing it can take a while."
- **Include text** (a chip, off by default, v0.4.1): when turned on, choose None, Captions, or
  Captions and note, sent as the share's text (the receiving app decides whether to show it).
  v0.4.0 always showed these options.
- The files go to the Android share chooser through the app's FileProvider (several files at
  once).
- The edit page's item sheet also has Share (one file) and Save to Device.

**Originals that are not on the phone** (v0.4.1): share-out downloads them into the encrypted
originals cache before decrypting (v0.4.0 skipped such items, so server-only photos came out
missing). Two minutes later the cache is trimmed back to **Original cache size**
([media-cache.md](media-cache.md)).

Decrypted copies sit in `cache/share/` and are released 60 s after sharing; the whole cache
directory is also wiped at app start, at unlock and on lock ([lock.md](lock.md)).

## Checked only on a phone

The Android share chooser with several files, the direct chooser for one entry, the sheet drawn
over the screen below, and sharing large videos.
