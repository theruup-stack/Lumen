# Lumen

Webbläsare och sökmotor med premiumdesign – för Android, Windows, macOS och Linux.

- `shared/ui` – gränssnittet (startsida, adressfält, flikar, bokmärken). Samma kod på alla plattformar.
- `android` – Android-appen (Kotlin). Varje flik är en egen WebView.
- `desktop` – datorprogrammet (Electron).
- `.github/workflows/build.yml` – bygger allt automatiskt vid varje ändring och lägger färdiga filer under **Releases**.

## Installera på Android
1. Öppna repots sida **Releases** i telefonen och ladda ner `Lumen-android.apk`.
2. Öppna filen. Första gången frågar Android om du vill tillåta installation från webbläsaren/filhanteraren – tillåt det.
3. Vill du att länkar öppnas i Lumen: Inställningar → Appar → Standardappar → Webbläsare → Lumen.

## iPhone
Kräver en Mac med Xcode och ett Apple-konto för att installeras, och ingår inte än.
