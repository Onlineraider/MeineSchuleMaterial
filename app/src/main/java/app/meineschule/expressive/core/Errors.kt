package app.meineschule.expressive.core

enum class ErrorKind { Network, Auth, Session, Forbidden, NotFound, Server, Client, Unsupported, Cancelled }

/** Fehler ohne sensible Inhalte (keine Tokens, Passwörter, Nachrichtentexte). */
class ApiException(val kind: ErrorKind, message: String, val status: Int = 0, cause: Throwable? = null) : Exception(message, cause) {
    val title: String get() = when (kind) {
        ErrorKind.Network -> "Keine Verbindung"; ErrorKind.Auth -> "Anmeldung erforderlich"; ErrorKind.Session -> "Bitte erneut anmelden"
        ErrorKind.Forbidden -> "Keine Berechtigung"; ErrorKind.NotFound -> "Nicht verfügbar"; ErrorKind.Server -> "Serverproblem"
        ErrorKind.Unsupported -> "Nicht unterstützt"; ErrorKind.Cancelled -> "Abgebrochen"; ErrorKind.Client -> "Das hat nicht geklappt"
    }
    val userText: String get() = when (kind) {
        ErrorKind.Network -> "Der Server ist gerade nicht erreichbar. Prüfe deine Internetverbindung und versuche es erneut."
        ErrorKind.Auth -> "Deine Sitzung ist abgelaufen. Bitte melde dich erneut an."
        ErrorKind.Session -> "Deine Sitzung konnte nicht erneuert werden. Melde dich bitte noch einmal an."
        ErrorKind.Forbidden -> "Für diesen Bereich hast du an deiner Schule keine Berechtigung."
        ErrorKind.NotFound -> "Diese Funktion oder dieser Inhalt wird von deiner Schule nicht bereitgestellt."
        ErrorKind.Server -> "Der Server hat einen Fehler gemeldet. Versuche es in einem Moment erneut."
        ErrorKind.Unsupported -> "Diese Funktion ist in der API nicht belegt und deshalb nicht verfügbar."
        ErrorKind.Cancelled -> "Der Vorgang wurde abgebrochen."
        ErrorKind.Client -> "Die Anfrage wurde vom Server abgelehnt."
    }
    val retryable: Boolean get() = kind == ErrorKind.Network || kind == ErrorKind.Server || kind == ErrorKind.Client

    companion object {
        fun fromStatus(status: Int) = ApiException(
            when { status == 401 -> ErrorKind.Auth; status == 403 -> ErrorKind.Forbidden; status == 404 -> ErrorKind.NotFound; status >= 500 -> ErrorKind.Server; else -> ErrorKind.Client },
            "HTTP $status", status,
        )
    }
}

fun Throwable.asApi(): ApiException = when (this) {
    is ApiException -> this
    is kotlinx.coroutines.CancellationException -> ApiException(ErrorKind.Cancelled, "cancelled", cause = this)
    is java.io.IOException -> ApiException(ErrorKind.Network, "io", cause = this)
    else -> ApiException(ErrorKind.Client, "unexpected", cause = this)
}
