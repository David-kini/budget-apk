# SmartBudget FC

Application Android de gestion financière personnelle, en franc congolais : répartition automatique des revenus par pourcentages, dépenses par catégorie, protection par PIN, exports Excel/PDF/Word et sauvegarde chiffrée portable.

- 100 % hors ligne : l'application ne déclare **aucune permission**, pas même l'accès Internet.
- Kotlin, Jetpack Compose, Material 3, Room + SQLCipher, architecture MVVM.

## Télécharger l'APK

Chaque modification de la branche `main` est compilée et testée automatiquement par GitHub Actions.
Si tous les tests réussissent, l'APK est publié ici :

**Releases → `dev-latest` → `SmartBudgetFC.apk`**

La même page contient les captures d'écran prises automatiquement sur l'émulateur Android.

Sur le téléphone, autorisez l'installation depuis votre navigateur ou votre gestionnaire de fichiers, puis ouvrez l'APK.

## Compiler soi-même

Prérequis : Android Studio (Ladybug ou plus récent), JDK 17.

```bash
./gradlew testDebugUnitTest   # tests unitaires et base de données
./gradlew assembleDebug       # APK : app/build/outputs/apk/debug/app-debug.apk
```

## État d'avancement

| Étape | Contenu | État |
|---|---|---|
| 1 | Analyse et architecture | ✅ voir `docs/ARCHITECTURE.md` |
| 2 | Base de données Room (schéma, triggers, chiffrement) | ✅ validé par la CI |
| 3 | Logique financière (répartition, dépenses, transferts, corrections) | ✅ 52 tests |
| 4 | Écrans Compose | ✅ test de bout en bout sur émulateur |
| 5 | Sécurité PIN | à faire |
| 6 | Blacklist | à faire |
| 7 | Exports XLSX / PDF / DOCX | à faire |
| 8 | Sauvegarde et restauration | à faire |
| 9 | Tests complets | à faire |
| 10 | Livraison | à faire |

## Signature

Les APK sont signés avec une clé de **développement** (`keystore/smartbudget-dev.jks`) identique à chaque compilation,
afin que les mises à jour s'installent sans perte de données. Avant toute diffusion publique,
remplacer cette clé par une clé privée stockée dans les secrets GitHub.
