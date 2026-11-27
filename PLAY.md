# Lumen på Google Play

Varje bygge skapar `Lumen-google-play.aab` (under Actions → bygget → Artifacts → google-play).
Det är filen som laddas upp till Google Play.

## Det här gör du en gång
1. Skapa ett utvecklarkonto på play.google.com/console (engångsavgift 25 USD, kräver ID-kontroll).
2. **Skapa app** → namn **Lumen**, språk **Svenska**, typ **App**, **Gratis**.
3. **Appintegritet → Appsignering:** välj *Använd Google Play App Signing*. Ladda upp `Lumen-google-play.aab`
   – nyckeln i projektet blir då uppladdningsnyckel.
4. **Testa först:** Testning → *Intern testning* → skapa version → ladda upp AAB → lägg till din e-post som testare.
5. Fyll i butikssidan (texter nedan), innehållsklassning, målgrupp (18+ eller alla) och dataskydd.
6. Nya konton måste köra **sluten testning med minst 12 testare i 14 dagar** innan appen får publiceras öppet.

## Butikstexter
**Kort beskrivning (80 tecken):**
Snabb och vacker webbläsare med annonsblockering, privata flikar och smarta bokmärken.

**Fullständig beskrivning:**
Lumen är en svensk webbläsare med fokus på design, integritet och bokmärken som faktiskt är trevliga att använda.

• Inbyggd annonsblockering – sidorna laddar snabbare, utan spårare
• Privata flikar som raderar allt när du stänger dem
• Bokmärken i en ren lista med bokstavsregister – håll inne för att spara sidan
• Bevaka sidor – få en prick när en sida ändras
• Arbetsytor med egna flikar och inloggningar
• Läsläge, läslista offline och spara som PDF
• Visuell historik – sök på ”igår” eller ”röd”
• Synka bokmärken och flikar med datorn
• Fokusläge som pausar sidor som stör
• Översätt sidor, röstsökning och QR-kodsläsare
• Välj sökmotor: Google, DuckDuckGo, Bing eller Ecosia

**Kategori:** Kommunikation (eller Verktyg)

## Integritetspolicy (krävs av Google – publicera som en offentlig webbsida)
Lumen samlar inte in några personuppgifter. Bokmärken, historik och inställningar sparas bara på din enhet.
Om du själv slår på synk sparas de i en privat gist på ditt eget GitHub-konto. Om du lägger in en egen
API-nyckel för Claude skickas sidans text till Anthropic när du använder ”Fråga sidan” eller Lumen-svar.
Sökningar går till den sökmotor du har valt. Lumen visar inga annonser och säljer ingen data.

## Behörigheter som Google frågar om
- Kamera, mikrofon, plats: bara när en webbplats ber om det och du tillåter.
- Installera appar: för Lumens inbyggda uppdatering. **Ta bort uppdateringsfunktionen i Play-versionen**
  (Google uppdaterar då appen) – säg till så gör jag en Play-variant.
- Förgrundstjänst för media: för ljud i bakgrunden.
