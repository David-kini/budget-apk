package cd.smartbudget.fc.core

/** Statut d'une opération financière. Une opération n'est jamais supprimée : elle est annulée. */
object OperationStatus {
    const val ACTIVE = "ACTIVE"
    const val CANCELLED = "CANCELLED"
}

/** Comportement quand une dépense dépasse le solde disponible de sa catégorie. */
enum class OverdraftMode {
    /** Refuser la dépense (par défaut). */
    REFUSE,

    /** Autoriser avec avertissement : le solde de la catégorie devient négatif. */
    WARN,

    /** Proposer un transfert depuis une autre catégorie, après confirmation. */
    TRANSFER,
}

/** Clés de la table app_settings. */
object SettingKeys {
    const val OVERDRAFT_MODE = "overdraft_mode"
    const val CURRENCY = "currency"
    const val SEEDED_AT = "seeded_at"
}
