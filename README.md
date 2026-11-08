# Isolateur

Application Android qui crée un **espace isolé** sur le téléphone et y place les applications
de votre choix, avec un contrôle complet : geler / dégeler, empêcher le démarrage au lancement du
téléphone, installer directement des APK dans l'espace, désinstaller.

## Principe technique

Android ne permet pas à une application ordinaire d'enfermer d'autres applis. Le seul mécanisme
d'isolation officiel sans root est le **profil professionnel** (Work Profile) : un second
utilisateur Android, avec ses propres données, comptes, fichiers et notifications. Isolateur crée ce
profil et en devient le **propriétaire** (Profile Owner), ce qui lui donne le droit de :

| Fonction | API utilisée |
|---|---|
| Copier une appli personnelle dans l'espace isolé | `DevicePolicyManager.installExistingPackage` |
| Geler une appli (plus aucune exécution, ni démarrage, ni notification) | `DevicePolicyManager.setApplicationHidden` |
| Bloquer le démarrage auto au lancement du téléphone | appli maintenue gelée ; dégel temporaire via « Lancer », regel à l'extinction de l'écran / à l'arrêt |
| Installer un APK directement dans l'espace | `PackageInstaller` (dans le profil) |
| Détecter les installations faites ailleurs (Play Store, APK, autre magasin) | service qui écoute `PACKAGE_ADDED` et propose « Isoler » par notification |

## Utilisation

1. Installer l'APK, ouvrir **Isolateur**, appuyer sur **Créer l'espace isolé** et suivre l'assistant Android.
2. Activer **Détecter les nouvelles installations** : chaque nouvelle appli déclenche une notification « La placer dans l'espace isolé ? ».
   « Isoler » copie l'appli dans l'espace puis ouvre la désinstallation de l'exemplaire personnel.
3. **Gérer l'espace isolé** (ou l'icône Isolateur avec badge 💼) : liste des applis isolées.
   - Interrupteur **Démarrage auto** désactivé → l'appli est gelée et ne peut plus démarrer avec le téléphone.
   - **Lancer** → dégel temporaire ; regel automatique à l'extinction de l'écran.
   - Menu ⋮ → Geler / Dégeler, infos système, désinstaller de l'espace.
   - Menu du haut → installer un APK, tout geler, afficher les applis système (ex. réactiver le Play Store de l'espace).

## Limites (imposées par Android, pas contournables sans root)

- **Interception automatique impossible** : Android ne laisse aucune appli détourner une installation
  vers un autre profil. Isolateur la détecte et vous propose l'isolation en un geste ; la suppression
  de l'exemplaire personnel demande votre confirmation.
- **Un seul profil professionnel par appareil** : si un profil pro d'entreprise existe déjà, l'espace ne peut pas être créé.
- Le blocage du démarrage auto repose sur le gel. Une appli autorisée (non gelée) peut démarrer au boot normalement.
- Les APK scindés (`.apks`, `.xapk`) ne sont pas pris en charge par l'installation directe (APK simple uniquement).
- Certains constructeurs (Xiaomi/MIUI notamment) restreignent les profils professionnels.

Android 9 (API 28) minimum.

## Compilation

```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Chaque push compile l'APK via GitHub Actions (onglet *Actions* → artefact `isolateur-debug-apk`).
