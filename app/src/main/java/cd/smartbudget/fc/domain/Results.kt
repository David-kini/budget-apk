package cd.smartbudget.fc.domain

/** Résultat d'une opération financière (entrée, dépense, transfert, annulation, correction, configuration). */
sealed interface OperationResult {
    /** L'opération a été enregistrée. */
    data class Recorded(val id: Long, val message: String) : OperationResult

    /** La même demande (même identifiant de requête) a déjà été enregistrée : rien n'a été dupliqué. */
    data class AlreadyRecorded(val id: Long) : OperationResult

    /** Refusée : aucune donnée n'a été modifiée. */
    data class Rejected(val reason: RejectReason, val message: String) : OperationResult

    /** Une confirmation de l'utilisateur est nécessaire : aucune donnée n'a été modifiée. */
    data class NeedsConfirmation(val confirmation: Confirmation) : OperationResult
}

enum class RejectReason {
    INVALID_INPUT,
    INSUFFICIENT_BALANCE,
    NO_CONFIGURATION,
    INVALID_CONFIGURATION,
    NOT_FOUND,
    ALREADY_CANCELLED,
    CATEGORY_INACTIVE,
    HAS_HISTORY,
    DUPLICATE_NAME,
    STILL_ALLOCATED,
}

sealed interface Confirmation {
    /** Mode « avertissement » : la dépense dépasse le solde disponible. */
    data class Overdraft(val categoryName: String, val available: Long, val shortfall: Long) : Confirmation

    /** Mode « transfert » : choisir la catégorie qui couvre le manque. */
    data class CoverByTransfer(
        val categoryName: String,
        val available: Long,
        val shortfall: Long,
        val candidates: List<CoverCandidate>,
    ) : Confirmation

    /** Une annulation rendrait des soldes négatifs. */
    data class NegativeBalances(val impacts: List<NegativeImpact>) : Confirmation
}

data class CoverCandidate(val categoryId: Long, val name: String, val balance: Long)

data class NegativeImpact(val categoryId: Long, val name: String, val balanceAfter: Long)

/** Sert à annuler une transaction Room en remontant un résultat métier (aucune écriture conservée). */
internal class AbortTransaction(val result: OperationResult) : RuntimeException(null, null, false, false)
