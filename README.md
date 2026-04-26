# 📦 Dispensa App

> **Gestisci la tua dispensa in modo semplice ed efficiente.**  
> A pantry manager to track what you have, avoid waste, and plan better.

# :camera: Screenshots

<img src="https://github.com/user-attachments/assets/eb90a318-a165-49f5-95aa-638942526294" width="200px" />
<img src="https://github.com/user-attachments/assets/b925093d-207f-4057-83b0-5517f41ee375" width="200px" />
<img src="https://github.com/user-attachments/assets/28f09e9e-03a6-4d16-af68-ea9e78bd222f" width="200px" />


## 🧰 Funzionalità principali

- 📝 Aggiungi, modifica ed elimina articoli nella tua dispensa (usa Open Food Facts per recuperare le informazioni)
- 🔔 Ricevi notifiche per prodotti in scadenza  
- 📦 Location personalizzabili
- 🔄 Sincronizzazione multi-dispositivo (rete locale e Google Drive)
- 🇮🇹 ligue supportate italiano ed inglese (it - en)

## 🚀 Installazione
- <a href="https://f-droid.org/packages/eu.frigo.dispensa">F-Droid</a> 
- <a href="https://play.google.com/store/apps/details?id=eu.frigo.dispensa">Play Store</a> 


### ✅ Prerequisiti
- Android Studio (per la versione di sviluppo)
- Android 14.0+ (per l'app su dispositivo)

---

## 🔄 Sync

Dispensa can keep multiple devices in sync using a CRDT-based change log built entirely on standard Android SQLite — no external library required.

### How it works

- A **`sync_changes` table** and 12 **SQLite triggers** capture every insert, update and delete on the four synced tables (`products`, `categories_definitions`, `product_category_links`, `storage_locations`).
- Each change carries a **Lamport clock** value so that the receiving device can apply **Last-Write-Wins** (LWW) conflict resolution per row.
- A stable **device UUID** (stored in `SharedPreferences`) breaks clock ties deterministically.
- Changes are serialised to a compact **JSON blob** (`SyncBlob`) and exchanged over the chosen transport.

### Transports

| Transport | Availability | How to enable |
|---|---|---|
| **Local network** (mDNS + TCP) | Both F-Droid and Play | Settings → Sync → *Enable local network sync* |
| **Google Drive** (`appDataFolder`) | Play Store only | Settings → Sync → *Enable Google Drive sync* then sign in |

### Background sync

WorkManager schedules a periodic sync every 15 minutes (when the device has a network connection). A **"Sync now"** button in Settings triggers an immediate one-shot sync.

### Architecture summary

```
AppDatabase  ──(triggers)──►  sync_changes table
                                    │
                            SyncManager
                     exportChanges() / importChanges()
                                    │
             ┌──────────────────────┴──────────────────────┐
             ▼                                             ▼
 LocalNetworkSyncTransport                  GoogleDriveSyncTransport
 (NsdManager mDNS + TCP)                   (Drive REST v3 appDataFolder)
 both flavors                               play flavor only
             └──────────────────────┬──────────────────────┘
                                    ▼
                             SyncWorker (WorkManager)
```
