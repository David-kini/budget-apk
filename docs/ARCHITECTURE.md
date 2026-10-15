# SmartBudget FC — Architecture

## 1. Choix techniques

| Élément | Choix |
|---|---|
| Langage / UI | Kotlin 2.0, Jetpack Compose, Material 3 |
| SDK | minSdk 26 (Android 8.0), targetSdk 35 |
| Base de données | SQLite via Room 2.6, chiffrée par SQLCipher |
| Clé de la base | 32 octets aléatoires, enveloppés par une clé AES-GCM Android Keystore (`no_backup/db.key`) |
| Injection | Conteneur manuel (`di/AppContainer`) |
| Excel / Word / PDF | fastexcel / générateur OOXML interne / `android.graphics.pdf.PdfDocument` |
| Fichiers | Sélecteur Android (Storage Access Framework) — aucune permission de stockage |
| Réseau | Aucun : pas de permission `INTERNET` |

## 2. Organisation du code

```
cd.smartbudget.fc
├── core/      Money (centimes), Percent (points de base), constantes
├── data/db/   AppDatabase, entités, DAO, triggers, données initiales
├── data/repo/ Dépôts (étape 3)
├── domain/    Répartition, soldes, cohérence (étape 3)
├── security/  Keystore, clé de base, PIN (étape 5)
├── backup/    Format .sbfbackup (étape 8)
├── export/    XLSX, PDF, DOCX (étape 7)
├── ui/        Thème, écrans Compose (étape 4)
└── di/        AppContainer
```

## 3. Conventions de données

- **Montants** : `Long` en centimes (1 FC = 100). Aucun `Float`/`Double`.
- **Pourcentages** : `Int` en points de base (10 000 = 100 %).
- **Dates métier** : jours depuis l'époque (`LocalDate.toEpochDay()`).
- **Identifiants portables** : `uuid` sur chaque enregistrement (utilisé par la sauvegarde).
- **Idempotence** : `clientRequestId` unique sur les entrées, dépenses et transferts.

## 4. Schéma (version 1)

| Table | Rôle |
|---|---|
| `categories` | Catégories (nom, couleur, ordre, active, épargne) |
| `budget_configurations` | Versions successives des pourcentages ; une seule `isCurrent` |
| `budget_configuration_items` | Pourcentage de chaque catégorie dans une configuration |
| `incomes` | Entrées d'argent, liées à la configuration appliquée |
| `allocations` | Part de chaque entrée par catégorie, avec le pourcentage appliqué |
| `expenses` | Dépenses, une catégorie obligatoire |
| `transfers` | Mouvements internes entre catégories |
| `blacklist_items`, `blacklist_history` | Module Blacklist et son historique |
| `app_settings` | Paramètres clé/valeur |
| `audit_log` | Journal des actions (dans la base chiffrée) |

Clés étrangères en `RESTRICT`. Le schéma JSON de chaque version est exporté dans `app/schemas/`.

## 5. Règle comptable

```
Solde(catégorie) = Σ allocations des entrées ACTIVES
                 − Σ dépenses ACTIVES
                 + Σ transferts ACTIFS reçus − Σ transferts ACTIFS envoyés

Solde total = Σ entrées actives − Σ dépenses actives
```

Les soldes ne sont jamais stockés : ils sont calculés en SQL (`BalanceDao`).

## 6. Intégrité garantie par SQLite (`DbTriggers`)

- Montants strictement positifs ; transfert vers la même catégorie interdit.
- Montant, catégorie et date d'une opération validée : immuables.
- Allocations et configurations de pourcentages : immuables.
- Une opération annulée ne peut pas être réactivée.
- Suppression des opérations financières interdite. Ces garde-fous ne sont retirés que pendant
  la transaction atomique de restauration, qui les recrée avant de valider.

Correction d'une opération = annulation (statut `CANCELLED`, motif, date) + nouvelle opération liée.

## 7. Répartition (étape 3)

Méthode du plus fort reste : partie entière de `montant × pb ÷ 10 000` pour chaque catégorie,
puis les centimes restants vont aux plus grands restes (égalité : ordre d'affichage, puis identifiant).
La somme est toujours exactement égale au montant reçu.

## 8. Migrations

Aucune destruction automatique (`fallbackToDestructiveMigration` interdit).
Chaque nouvelle version du schéma ajoute une migration explicite dans `Migrations.ALL` et un test `MigrationTestHelper`.
