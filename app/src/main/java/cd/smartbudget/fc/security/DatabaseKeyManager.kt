package cd.smartbudget.fc.security

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.SecureRandom

/**
 * Fournit la phrase secrète SQLCipher de la base locale.
 *
 * - 32 octets aléatoires générés au premier lancement ;
 * - stockés uniquement sous forme chiffrée par une clé Android Keystore (fichier db.key, dossier no_backup) ;
 * - propres à CE téléphone : le transfert vers un autre appareil passe exclusivement par la
 *   sauvegarde .sbfbackup protégée par le mot de passe de sauvegarde.
 */
class DatabaseKeyManager(context: Context) {

    private val file = AtomicFile(File(context.noBackupFilesDir, KEY_FILE))
    private val cipher = KeystoreCipher(KEY_ALIAS)

    @Synchronized
    fun getOrCreatePassphrase(): ByteArray {
        if (file.baseFile.exists()) {
            return cipher.decrypt(file.readFully())
        }
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encrypted = cipher.encrypt(passphrase)
        val out = file.startWrite()
        try {
            out.write(encrypted)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        return passphrase
    }

    /** Indique si une base chiffrée a déjà été initialisée sur ce téléphone. */
    fun hasKey(): Boolean = file.baseFile.exists()

    companion object {
        private const val KEY_FILE = "db.key"
        private const val KEY_ALIAS = "smartbudget_db_key_wrapper"
    }
}
