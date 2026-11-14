# Shared journals and share to apps

Two ways to share: a **shared journal** that other accounts on the same server can read, and
**share to apps**, which hands decrypted files to another app. Public links are in
[public-links.md](public-links.md). Code: `apps/server/src/services/sharing-service.ts`,
`apps/client/src/services/sharing/`, `apps/client/src/features/sharing/`,
`apps/client/src/adapters/share-files/`.

## The Share sheet

Opened from the viewer's action row and ⋮, the edit page, and the gallery's bulk actions. It has
two tabs; **In Kodakiko** comes first when the server has sharing or public links turned on and
the account is not local-only, otherwise only **To apps** shows.

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

- **Shared tab:** "My Shared Journals" (name, cover, number of entries, number of people) with
  **New Shared Journal…** (name 1–100 characters), and "Shared With Me" (name, "from (owner)"). Empty
  states: "You haven't shared anything yet." / "Nothing has been shared with you yet." With the
  flag off: "Sharing is turned off on this server."
- **A journal** (`/collections/[id]`) shows its entries in the gallery grid. The owner's ⋮: Share
  With…, Rename…, Delete Journal… ("The entries stay in your journal. People you shared it with lose
  access."); selection offers Remove from This Journal. A recipient sees "Shared by (owner)" and
  ⋮ Leave… ("You'll lose access unless (owner) shares it again.").
- **Share With…** (`/collections/[id]/share`): find a username, compare the security code, Share;
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

- A picker grid of every item of the chosen entries, all checked; tap to uncheck. The button shows
  the count and total size ("Share 3 items · 24 MB"); above 500 MB it warns "That is (size).
  Decrypting and sharing it can take a while."
- **Text:** None, Captions, or Captions and note, sent as the share's text (the receiving app
  decides whether to show it).
- A note says the files leave Kodakiko unencrypted.
- Android uses `react-native-share` (several files at once); the web uses
  `navigator.share({ files })` where the browser supports it, otherwise the tab says "This browser
  can't share files. Use Save to Device instead."
- Each item's ⋮ on the edit page also has Share (one file) and Save to Device.

Decrypted copies are released 60 s after sharing; on Android all temp files are also deleted on
lock and at app start.

## Checked only on a phone

The Android share sheet with several files, and sharing large videos.
