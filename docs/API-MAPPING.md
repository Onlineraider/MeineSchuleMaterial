# API → Screen (nur belegte Endpunkte)

Basis: `{origin}/Api/v1.0`; global `App/App/…`, schulbezogen `App/{school}/…`.

| Screen | Endpunkte |
|---|---|
| Login | `POST Token/CreateToken` (`twoFactorNeeded` → Code), `POST Token/RefreshToken` |
| Start / Profil | `GET App/App/Profile`, `School/Index`, `School/State`, `POST App/App/Users` |
| Stundenplan | Portal: `POST App/App/OneTimeToken` → `Account/Login` (Cookie) → Schedule-JSON |
| Messenger | `Messenger/Index|Categories|Chat/{id}[/Items|ItemsBefore|ItemsAfter|Info|Mute|Pin|MarkAsUnread|MarkAsRead|Delete|Forward|Report|Vote|Upload]`, `School/Recipients`, `School/SearchRecipients`; Echtzeit `{origin}/notifyHub` Event `newmessage` |
| Kalender | `Calendar/Index|Detail|Confirm` |
| Medien | `Media/Categories|Index|Detail|Confirm|ToggleLike`, Web `Media/Image|Download` |
| Home.InfoPoint | `HIP/Index`, `HIP/Student/{id}` (HTML → bereinigt + nativ geparst) |
| Abwesenheiten | `Absence/Index/{date}`, `Absence/Report/Index`, `…/Student/{id}[/{reportId}[/Attachment]]` |
| Betreuung | `AfterSchoolCare/Parent/Index|{date}|Student/{id}/{date}` |
| Fundsachen | `LostAndFound/Index`, `POST/PATCH LostAndFound/`, `DELETE …/{id}`, `…/{id}/Attachment`, Web `Image` |
| Protokolle | `Protocol/Index|Detail`, Web `Protocol/{id}/download/{att}` |
| Schüler | `School/Student/{id}` (Fachlehrkräfte aus `subjectInfo.details[]`), `School/UserInfo`, Klassen/Gruppen |
| Weitere Links | `School/Index.links[]` (Validierung in der App) |
| Hinweise | `School/State` |

## Nicht belegt → nicht umgesetzt
Push-Registrierung (DeviceInstallation), Kalender ablehnen, Umfrage abstimmen (Modell unklar), Erstellen von Kalender/Protokoll/Medien, Bearbeiten von Schülereigenschaften in der App, Stundenplan-Zeiten/Räume.

## Annahmen (bei echten Antworten prüfen)
Feldnamen undokumentierter Modelle (tolerant gemappt); Chat-Upload via `Upload/{tempId}` + `tempId` beim Senden; Empfänger als Schlüssel-Strings; `Items` Seite 1 = neueste; `appId` muss vom Nutzer geliefert werden.
