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

## Shizuku (recommandé)

[Shizuku](https://shizuku.rikka.app/) donne à Isolateur les droits ADB **sans root**. Il débloque :

- la **désinstallation des applis système** du profil personnel (`pm uninstall --user 0`, restaurable) ;
- l'**isolation silencieuse** : chaque nouvelle appli est déplacée dans l'espace isolé sans confirmation ni notification ;
- le **mode par défaut** : le Play Store personnel est désactivé, le seul Play Store restant est celui de
  l'espace isolé, donc tout téléchargement s'installe directement dedans.

Mise en route : installer Shizuku, le démarrer via *Débogage sans fil* (Android 11+), puis appuyer sur
« Autoriser » dans Isolateur. Shizuku doit être redémarré après chaque redémarrage du téléphone
(sans root).

Les applis indispensables au téléphone (interface système, Paramètres, lanceur, clavier, services
Google…) sont protégées contre la suppression.

## Discrétion

- Aucune icône Isolateur dans l'espace isolé ; tout se gère depuis le côté personnel.
- Notifications de service en priorité minimale et masquées sur l'écran de verrouillage
  (sur Android 13+ on peut les balayer ; on peut aussi couper leur canal dans les réglages de l'appli).
- **Écran d'accueil Isolateur** (bouton « Utiliser l'écran d'accueil Isolateur ») : toutes les applis,
  personnelles et isolées, dans une seule grille triée par nom, avec leurs icônes **sans badge** et
  **sans onglet « Travail »**. Rien n'y distingue une appli isolée. Recherche en haut, appui long =
  infos / désinstaller, roue dentée = réglages Isolateur. Une appli présente des deux côtés n'apparaît
  qu'une fois.
- **Restent visibles, car dessinés par Android lui-même** : petit badge 💼 sur les notifications et
  dans l'écran des applis récentes, mention « profil professionnel » dans les Paramètres.
- Les applis gelées (démarrage auto bloqué) n'apparaissent pas sur l'écran d'accueil tant qu'elles
  sont gelées : on les lance depuis « Gérer l'espace isolé ».

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

## Tout désinstaller

Isolateur est installé deux fois : côté personnel et dans l'espace isolé, dont il est
l'administrateur. Désinstaller la copie personnelle ne suffit pas. Utiliser le menu
**⋮ → Supprimer l'espace isolé et désinstaller Isolateur** : il réactive le Play Store personnel,
supprime l'espace (avec toutes ses applis et données) puis propose la désinstallation.

À la main : Paramètres → Comptes → Profil professionnel → Supprimer le profil professionnel.

## Compilation

```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Chaque push compile l'APK via GitHub Actions (onglet *Actions* → artefact `isolateur-debug-apk`).
