import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProGymApp {

    // Codice Segreto d'accesso invisibile per il Proprietario
    public static final String CODICE_PROPRIETARIO = "PROGYM2026ADMIN";

    // Database in-memory degli iscritti PROGYM
    public static final Map<String, Utente> utentiDb = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        int porta = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(porta), 0);

        server.createContext("/", new StaticHandler());
        server.createContext("/api/register", new RegisterHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/checkin", new CheckinHandler());
        server.createContext("/api/owner", new OwnerHandler());

        server.setExecutor(null);
        System.out.println("==================================================");
        System.out.println("  PROGYM APP - SERVER AVVIATO SULLA PORTA " + porta);
        System.out.println("  Accesso Locale: http://localhost:" + porta);
        System.out.println("  Codice Segreto Proprietario: " + CODICE_PROPRIETARIO);
        System.out.println("==================================================");
        server.start();
    }

    // Modello Dati Iscritto
    public static class Utente {
        public String nome;
        public String cognome;
        public String codiceFiscale;
        public String email;
        public String telefono;
        public int giornoNascita;
        public int meseNascita;
        public int annoNascita;
        public int giornoPagamento;
        public int mesePagamento;
        public String password;
        public String attivita;
        public String orario;
        public String certificatoMedicoStatus;
        public List<String> storicoIngressi = new ArrayList<>();

        public Utente(String nome, String cognome, String codiceFiscale, String email, String telefono,
                      int giornoNascita, int meseNascita, int annoNascita,
                      int giornoPagamento, int mesePagamento, String password,
                      String attivita, String orario, String certificatoMedicoStatus) {
            this.nome = nome;
            this.cognome = cognome;
            this.codiceFiscale = codiceFiscale;
            this.email = email;
            this.telefono = telefono;
            this.giornoNascita = giornoNascita;
            this.meseNascita = meseNascita;
            this.annoNascita = annoNascita;
            this.giornoPagamento = giornoPagamento;
            this.mesePagamento = mesePagamento;
            this.password = password;
            this.attivita = attivita;
            this.orario = orario;
            this.certificatoMedicoStatus = certificatoMedicoStatus;
        }

        // Calcola la data di scadenza dinamica mensile
        public LocalDate getDataScadenzaCorrente() {
            LocalDate oggi = LocalDate.now();
            int annoCorrente = oggi.getYear();
            
            // Imposta la data di scadenza per il mese di pagamento corrente/prossimo
            LocalDate dataPagamento = LocalDate.of(annoCorrente, mesePagamento, Math.min(giornoPagamento, 28));
            
            // Se la data di pagamento iniziale è passata, la scadenza si sposta al mese successivo
            LocalDate dataScadenza = dataPagamento.plusMonths(1);
            while (oggi.isAfter(dataScadenza)) {
                dataScadenza = dataScadenza.plusMonths(1);
            }
            return dataScadenza;
        }

        // Calcolo dello Stato Pagamento (VERDE, ARANCIONE, ROSSO)
        public String getStatoColore() {
            LocalDate oggi = LocalDate.now();
            LocalDate dataScadenza = getDataScadenzaCorrente();

            if (oggi.isAfter(dataScadenza)) {
                return "ROSSO"; // Scaduto
            }
            long giorniRimanenti = ChronoUnit.DAYS.between(oggi, dataScadenza);
            if (giorniRimanenti <= 5) {
                return "ARANCIONE"; // Sta per scadere (5 o meno giorni)
            }
            return "VERDE"; // Pagato
        }

        public long getGiorniMancanti() {
            return ChronoUnit.DAYS.between(LocalDate.now(), getDataScadenzaCorrente());
        }
    }

    // Gestione Registrazione Iscritto
    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> params = parseFormData(exchange);

                String pass = params.get("password");
                String confirmPass = params.get("confirmPassword");

                // Controllo corrispondenza password
                if (!pass.equals(confirmPass)) {
                    sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Le password non coincidono!\"}");
                    return;
                }

                String email = params.get("email");
                Utente u = new Utente(
                    params.get("nome"),
                    params.get("cognome"),
                    params.get("codiceFiscale"),
                    email,
                    params.get("telefono"),
                    Integer.parseInt(params.getOrDefault("giornoNascita", "1")),
                    Integer.parseInt(params.getOrDefault("meseNascita", "1")),
                    Integer.parseInt(params.getOrDefault("annoNascita", "2000")),
                    Integer.parseInt(params.getOrDefault("giornoPagamento", "1")),
                    Integer.parseInt(params.getOrDefault("mesePagamento", "1")),
                    pass,
                    params.get("attivita"),
                    params.get("orario"),
                    params.getOrDefault("certificato", "Certificato_Allegato.pdf")
                );

                utentiDb.put(email, u);
                sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Iscrizione completata con successo in PROGYM!\"}");
            }
        }
    }

    // Gestione Login Cliente e Accesso Proprietario Invisibile
    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> params = parseFormData(exchange);
                String loginInput = params.get("email");
                String pass = params.get("password");

                // VERIFICA ACCESSO SEGRETO PROPRIETARIO
                if (CODICE_PROPRIETARIO.equals(loginInput) || CODICE_PROPRIETARIO.equals(pass)) {
                    sendResponse(exchange, 200, "{\"success\":true, \"isOwner\":true}");
                    return;
                }

                Utente u = utentiDb.get(loginInput);
                if (u != null && u.password.equals(pass)) {
                    String json = String.format(Locale.US,
                        "{\"success\":true,\"isOwner\":false,\"nome\":\"%s %s\",\"attivita\":\"%s\",\"orario\":\"%s\",\"scadenza\":\"%s\",\"giorniMancanti\":%d,\"stato\":\"%s\",\"ingressi\":%d}",
                        u.nome, u.cognome, u.attivita, u.orario, u.getDataScadenzaCorrente().toString(), u.getGiorniMancanti(), u.getStatoColore(), u.storicoIngressi.size());
                    sendResponse(exchange, 200, json);
                } else {
                    sendResponse(exchange, 401, "{\"success\":false,\"message\":\"Credenziali non valide\"}");
                }
            }
        }
    }

    // Gestione Registro Ingressi
    static class CheckinHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> params = parseFormData(exchange);
                String email = params.get("email");
                Utente u = utentiDb.get(email);
                if (u != null) {
                    String dataOra = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " - Ore " + params.getOrDefault("ora", "18:00");
                    u.storicoIngressi.add(dataOra);
                    sendResponse(exchange, 200, "{\"success\":true, \"totale\":" + u.storicoIngressi.size() + "}");
                } else {
                    sendResponse(exchange, 404, "{\"success\":false}");
                }
            }
        }
    }

    // Gestione Scheda Fissa Proprietario
    static class OwnerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> params = parseFormData(exchange);
                String codice = params.get("code");

                if (!CODICE_PROPRIETARIO.equals(codice)) {
                    sendResponse(exchange, 403, "{\"success\":false, \"message\":\"Accesso Negato!\"}");
                    return;
                }

                StringBuilder jsonList = new StringBuilder("[");
                int count = 0;
                for (Utente u : utentiDb.values()) {
                    if (count > 0) jsonList.append(",");
                    jsonList.append(String.format(Locale.US,
                        "{\"nome\":\"%s %s\",\"cf\":\"%s\",\"telefono\":\"%s\",\"attivita\":\"%s\",\"orario\":\"%s\",\"certificato\":\"%s\",\"scadenza\":\"%s\",\"stato\":\"%s\"}",
                        u.nome, u.cognome, u.codiceFiscale, u.telefono, u.attivita, u.orario, u.certificatoMedicoStatus, u.getDataScadenzaCorrente().toString(), u.getStatoColore()));
                    count++;
                }
                jsonList.append("]");

                sendResponse(exchange, 200, "{\"success\":true, \"iscritti\":" + jsonList.toString() + "}");
            }
        }
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = getHtmlContent();
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }

    private static Map<String, String> parseFormData(HttpExchange exchange) throws IOException {
        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        String query = br.readLine();
        Map<String, String> map = new HashMap<>();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1) {
                    map.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
            }
        }
        return map;
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    // Interfaccia Web Responsive (Ottimizzata Mobile First)
    private static String getHtmlContent() {
        return "<!DOCTYPE html><html lang='it'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
            + "<title>PROGYM App</title>"
            + "<style>"
            + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin:0; padding:15px; background:#121212; color:#fff; }"
            + "h1, h2 { text-align:center; color:#00e676; margin-bottom:15px; }"
            + ".card { background:#1e1e1e; padding:20px; border-radius:12px; margin-bottom:20px; border:1px solid #333; }"
            + "label { font-size:0.85em; color:#bbb; display:block; margin-top:10px; }"
            + "input, select, button { width:100%; padding:12px; margin-top:5px; border-radius:8px; border:1px solid #444; background:#2a2a2a; color:#fff; box-sizing:border-box; font-size:1em; }"
            + "button { background:#00e676; color:#000; font-weight:bold; border:none; margin-top:15px; cursor:pointer; }"
            + ".date-row { display:flex; gap:5px; }"
            + ".badge { padding:6px 12px; border-radius:20px; font-weight:bold; display:inline-block; font-size:0.85em; }"
            + ".badge-VERDE { background:#2e7d32; color:#fff; }"
            + ".badge-ARANCIONE { background:#ef6c00; color:#fff; }"
            + ".badge-ROSSO { background:#c62828; color:#fff; }"
            + "table { width:100%; border-collapse:collapse; margin-top:10px; font-size:0.85em; }"
            + "th, td { padding:8px; text-align:left; border-bottom:1px solid #333; }"
            + "th { background:#2a2a2a; color:#00e676; }"
            + "</style></head><body>"
            + "<h1>🏋️ PROGYM</h1>"

            // FORM REGISTRAZIONE CLIENTE
            + "<div class='card' id='mainPage'>"
            + "<h2>Iscrizione Cliente</h2>"
            + "<form id='regForm' onsubmit='register(event)'>"
            + "<input type='text' id='nome' placeholder='Nome' required>"
            + "<input type='text' id='cognome' placeholder='Cognome' required>"
            + "<input type='text' id='codiceFiscale' placeholder='Codice Fiscale' required>"
            + "<input type='email' id='email' placeholder='Email' required>"
            + "<input type='tel' id='telefono' placeholder='Numero di Telefono' required>"
            + "<label>Data di Nascita:</label>"
            + "<div class='date-row'>"
            + "<input type='number' id='gg' placeholder='Giorno (GG)' min='1' max='31' required>"
            + "<input type='number' id='mm' placeholder='Mese (MM)' min='1' max='12' required>"
            + "<input type='number' id='aa' placeholder='Anno (AAAA)' min='1930' max='2026' required>"
            + "</div>"
            + "<label>Data Effettuazione Pagamento Mensilità:</label>"
            + "<div class='date-row'>"
            + "<input type='number' id='ggPag' placeholder='Giorno Pagamento' min='1' max='31' required>"
            + "<input type='number' id='mmPag' placeholder='Mese Pagamento' min='1' max='12' required>"
            + "</div>"
            + "<input type='password' id='pass' placeholder='Password' required>"
            + "<input type='password' id='confirmPass' placeholder='Conferma Password' required>"
            + "<label>Attività / Corso:</label>"
            + "<select id='attivita'>"
            + "<option>Sala Pesi</option>"
            + "<option>Danza</option>"
            + "<option>Balli di Gruppo</option>"
            + "<option>Pugilato</option>"
            + "<option>Pilates</option>"
            + "<option>Bungee Fly</option>"
            + "<option>Pole Dance</option>"
            + "<option>Step</option>"
            + "</select>"
            + "<label>Orario Preferito:</label>"
            + "<select id='orario'><option>08:00 - 10:00</option><option>10:00 - 12:00</option><option>15:00 - 17:00</option><option>18:00 - 20:00</option></select>"
            + "<label>Allegato Certificato Medico (PDF / Foto):</label>"
            + "<input type='file' id='certificato' accept='image/*,.pdf'>"
            + "<button type='submit'>Completa Iscrizione</button>"
            + "</form>"
            + "</div>"

            // ACCESSO UNICO (CLIENTE O PROPRIETARIO CON CODICE SEGRETO)
            + "<div class='card' id='loginCard'>"
            + "<h2>Accedi alla Palestra</h2>"
            + "<input type='text' id='loginEmail' placeholder='Email o Codice Riservato'>"
            + "<input type='password' id='loginPass' placeholder='Password'>"
            + "<button onclick='login()'>Accedi</button>"
            + "</div>"

            // SCHERMATA CLIENTE
            + "<div class='card' id='clientDash' style='display:none;'>"
            + "<h2>Area Personale Cliente</h2>"
            + "<div id='clientInfo'></div>"
            + "<button onclick='doCheckin()'>📍 Registra Ingresso del Giorno</button>"
            + "</div>"

            // PAGINA SEPARATA RISERVATA PROPRIETARIO
            + "<div class='card' id='ownerDash' style='display:none; background:#181818; border:2px solid #ff9800;'>"
            + "<h2 style='color:#ff9800;'>👑 PAGINA PROPRIETARIO - PROGYM</h2>"
            + "<p style='text-align:center; color:#aaa;'>Pannello di Controllo Iscritti e Pagamenti</p>"
            + "<div style='overflow-x:auto;'>"
            + "<table><thead><tr><th>Nome e Cognome</th><th>Codice Fiscale</th><th>Telefono</th><th>Corso / Orario</th><th>Certificato</th><th>Stato Mensilità</th></tr></thead>"
            + "<tbody id='ownerTableBody'></tbody></table>"
            + "</div>"
            + "<button style='background:#f44336; color:#fff;' onclick='location.reload()'>Esci dall'Area Proprietario</button>"
            + "</div>"

            // LOGICA JAVASCRIPT
            + "<script>"
            + "async function register(e) {"
            + "  e.preventDefault();"
            + "  const pass = document.getElementById('pass').value;"
            + "  const confirm = document.getElementById('confirmPass').value;"
            + "  if(pass !== confirm) { alert('Attenzione: La conferma password non coincide con la password!'); return; }"
            + "  const body = 'nome=' + encodeURIComponent(document.getElementById('nome').value) +"
            + "    '&cognome=' + encodeURIComponent(document.getElementById('cognome').value) +"
            + "    '&codiceFiscale=' + encodeURIComponent(document.getElementById('codiceFiscale').value) +"
            + "    '&email=' + encodeURIComponent(document.getElementById('email').value) +"
            + "    '&telefono=' + encodeURIComponent(document.getElementById('telefono').value) +"
            + "    '&giornoNascita=' + document.getElementById('gg').value +"
            + "    '&meseNascita=' + document.getElementById('mm').value +"
            + "    '&annoNascita=' + document.getElementById('aa').value +"
            + "    '&giornoPagamento=' + document.getElementById('ggPag').value +"
            + "    '&mesePagamento=' + document.getElementById('mmPag').value +"
            + "    '&password=' + encodeURIComponent(pass) +"
            + "    '&confirmPassword=' + encodeURIComponent(confirm) +"
            + "    '&attivita=' + encodeURIComponent(document.getElementById('attivita').value) +"
            + "    '&orario=' + encodeURIComponent(document.getElementById('orario').value);"
            + "  const res = await fetch('/api/register', { method:'POST', body: body });"
            + "  const data = await res.json();"
            + "  alert(data.message);"
            + "  if(data.success) document.getElementById('regForm').reset();"
            + "}"
            
            + "async function login() {"
            + "  const loginInput = document.getElementById('loginEmail').value;"
            + "  const pass = document.getElementById('loginPass').value;"
            + "  const res = await fetch('/api/login', { method:'POST', body: 'email=' + encodeURIComponent(loginInput) + '&password=' + encodeURIComponent(pass) });"
            + "  const data = await res.json();"
            
            // SE VIENE INSERITO IL CODICE SEGRETO PROPRIETARIO APRE LA PAGINA APPARTE
            + "  if(data.isOwner) {"
            + "    document.getElementById('mainPage').style.display = 'none';"
            + "    document.getElementById('loginCard').style.display = 'none';"
            + "    document.getElementById('clientDash').style.display = 'none';"
            + "    document.getElementById('ownerDash').style.display = 'block';"
            + "    loadOwnerData(loginInput);"
            + "    return;"
            + "  }"
            
            + "  if(data.success) {"
            + "    document.getElementById('clientDash').style.display = 'block';"
            + "    let notifica = '';"
            + "    if(data.stato === 'ROSSO') notifica = '<div style=\"background:#c62828; padding:12px; margin-top:10px; border-radius:8px; font-weight:bold;\">⚠️ NOTIFICA: La tua mensilità è SCADUTA! Si prega di rinnovare.</div>';"
            + "    else if(data.stato === 'ARANCIONE') notifica = '<div style=\"background:#ef6c00; padding:12px; margin-top:10px; border-radius:8px; font-weight:bold;\">⏳ NOTIFICA: La tua mensilità sta per scadere tra ' + data.giorniMancanti + ' giorni!</div>';"
            + "    else notifica = '<div style=\"background:#2e7d32; padding:12px; margin-top:10px; border-radius:8px; font-weight:bold;\">✅ MENSILITÀ REGOLARE / PAGATO</div>';"
            + "    document.getElementById('clientInfo').innerHTML = '<h3>Benvenuto ' + data.nome + '</h3><p>Corso Iscritto: <b>' + data.attivita + '</b> (' + data.orario + ')</p><p>Data Scadenza Prossimo Rinnovo: <b>' + data.scadenza + '</b></p><p>Ingressi Effettuati Mese: <b>' + data.ingressi + '</b></p>' + notifica;"
            + "  } else { alert(data.message); }"
            + "}"

            + "async function loadOwnerData(code) {"
            + "  const res = await fetch('/api/owner', { method:'POST', body: 'code=' + encodeURIComponent(code) });"
            + "  const data = await res.json();"
            + "  if(data.success) {"
            + "    const tbody = document.getElementById('ownerTableBody');"
            + "    tbody.innerHTML = '';"
            + "    data.iscritti.forEach(i => {"
            + "      tbody.innerHTML += '<tr><td><b>' + i.nome + '</b></td><td>' + i.cf + '</td><td>' + i.telefono + '</td><td>' + i.attivita + ' <br><small>' + i.orario + '</small></td><td>' + i.certificato + '</td><td><span class=\"badge badge-' + i.stato + '\">' + (i.stato==='VERDE'?'PAGATO':i.stato==='ARANCIONE'?'IN SCADENZA':'SCADUTO') + '</span></td></tr>';"
            + "    });"
            + "  }"
            + "}"

            + "async function doCheckin() {"
            + "  const email = document.getElementById('loginEmail').value;"
            + "  const res = await fetch('/api/checkin', { method:'POST', body: 'email=' + encodeURIComponent(email) });"
            + "  const data = await res.json();"
            + "  if(data.success) { alert('Ingresso registrato! Totale ingressi: ' + data.totale); login(); }"
            + "}"
            + "</script>"
            + "</body></html>";
    }
}