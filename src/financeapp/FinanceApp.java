package financeapp;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.*;

// ─────────────────────────────────────────────────────────────────────────────
//  Finance Pro  ·  Professional Edition  ·  All-in-one source
// ─────────────────────────────────────────────────────────────────────────────
public class FinanceApp extends Application {

    // ── Database ──────────────────────────────────────────────────────────────
    private static final String DB_HOST  = "jdbc:mysql://localhost:3306/";
    private static final String DB_NAME  = "finance_pro_db";
    private static final String DB_URL   = DB_HOST + DB_NAME
            + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    private static final String DB_USER  = "root";
    private static final String DB_PASS  = "";          // ← set your password

    private static final Logger LOG = Logger.getLogger(FinanceApp.class.getName());

    // ── Session ───────────────────────────────────────────────────────────────
    private Stage   stage;
    private int     userId   = -1;
    private String  username = "";
    private String  fullName = "";

    // ── Layout skeleton ───────────────────────────────────────────────────────
    private BorderPane root;
    private StackPane  workspace;
    private final Map<String, javafx.scene.Node> cards      = new LinkedHashMap<>();
    private final Map<String, Button>            navBtns    = new HashMap<>();

    // ── Dashboard live labels ─────────────────────────────────────────────────
    private Label lbTotalIncome, lbTotalExpense, lbBalance;
    private Label lbTodayIncome, lbTodayExpense, lbMonthIncome, lbMonthExpense;
    private Label lbBudgetHealth, lbSavingsRate, lbCashFlow, lbTopCategory;
    private PieChart               pieChart;
    private BarChart<String,Number> barChart;
    private CategoryAxis barX; private NumberAxis barY;

    // ── Transactions pane ─────────────────────────────────────────────────────
    private TableView<Transaction>      txTable;
    private ObservableList<Transaction> txData;
    private ComboBox<String> txCatFilter;
    private DatePicker       txFromDate, txToDate;
    private TextField        txSearch;

    // ── Budgets pane ──────────────────────────────────────────────────────────
    private TableView<Budget>      budgetTable;
    private ObservableList<Budget> budgetData;

    // ── Sidebar ───────────────────────────────────────────────────────────────
    private ListView<String> notifList;
    private Label            lbSideStats;

    // ── Theme ─────────────────────────────────────────────────────────────────
    private boolean dark = false;
    private Button  themeBtn;

    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage s) {
        this.stage = s;
        if (!initDb()) {
            alert(AlertType.ERROR, "DB Error",
                  "Cannot connect to MySQL.\n• MySQL running?\n• Password correct?\n• Port 3306 open?");
            Platform.exit();
            return;
        }
        root = new BorderPane();
        root.setStyle(BG_LIGHT);
        showLogin();
        stage.setTitle("Finance Pro – Professional Edition");
        stage.setMaximized(true);
        stage.setMinWidth(1100); stage.setMinHeight(680);
        stage.setScene(new Scene(root));
        stage.show();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DATABASE
    // ══════════════════════════════════════════════════════════════════════════
    private boolean initDb() {
        try (Connection c = DriverManager.getConnection(DB_HOST, DB_USER, DB_PASS);
             Statement  s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS " + DB_NAME);
        } catch (SQLException e) { LOG.severe(e.getMessage()); return false; }

        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             Statement  s = c.createStatement()) {

            s.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    username  VARCHAR(50)  UNIQUE NOT NULL,
                    password  VARCHAR(255) NOT NULL,
                    full_name VARCHAR(100),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_u (username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");

            s.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id   INT PRIMARY KEY AUTO_INCREMENT,
                    user_id INT NOT NULL,
                    type     VARCHAR(10)  NOT NULL,
                    category VARCHAR(50)  NOT NULL,
                    amount   DECIMAL(15,2) NOT NULL,
                    description TEXT,
                    transaction_date DATE NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_ud (user_id, transaction_date),
                    INDEX idx_ut (user_id, type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");

            s.execute("""
                CREATE TABLE IF NOT EXISTS budgets (
                    id        INT PRIMARY KEY AUTO_INCREMENT,
                    user_id   INT NOT NULL,
                    category  VARCHAR(50) NOT NULL,
                    amount    DECIMAL(15,2) NOT NULL,
                    month_year VARCHAR(7)  NOT NULL,
                    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    UNIQUE KEY uk_ucm (user_id, category, month_year),
                    INDEX idx_bum (user_id, month_year)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");

            ResultSet rs = s.executeQuery("SELECT id FROM users WHERE username='admin'");
            if (!rs.next()) {
                s.executeUpdate(
                    "INSERT INTO users (username,password,full_name) VALUES ('admin','admin123','System Administrator')");
                rs = s.executeQuery("SELECT id FROM users WHERE username='admin'");
                if (rs.next()) seedSampleData(s, rs.getInt("id"));
            }
            return true;
        } catch (SQLException e) { LOG.severe(e.getMessage()); return false; }
    }

    private void seedSampleData(Statement s, int uid) throws SQLException {
        String[] rows = {
            "('INCOME','Salary',5000.00,'Monthly salary')",
            "('INCOME','Freelance',1200.00,'Web project')",
            "('EXPENSE','Rent',1200.00,'Monthly rent')",
            "('EXPENSE','Food & Dining',450.00,'Groceries')",
            "('EXPENSE','Utilities',180.00,'Electricity bill')",
            "('EXPENSE','Transportation',200.00,'Gas & transit')",
            "('EXPENSE','Entertainment',150.00,'Streaming + movies')",
            "('EXPENSE','Shopping',320.00,'Clothing')",
            "('EXPENSE','Healthcare',90.00,'Pharmacy')"
        };
        for (String r : rows)
            s.execute("INSERT INTO transactions (user_id,type,category,amount,description,transaction_date) "
                    + "VALUES (" + uid + "," + r + ",CURDATE())");
        // seed default budgets
        String[] bRows = {
            "('Food & Dining',500.00)", "('Transportation',250.00)",
            "('Entertainment',200.00)", "('Shopping',400.00)"
        };
        String mon = YearMonth.now().toString();
        for (String b : bRows)
            s.execute("INSERT IGNORE INTO budgets (user_id,category,amount,month_year) VALUES ("
                    + uid + "," + b + ",'" + mon + "')");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AUTH SCREENS
    // ══════════════════════════════════════════════════════════════════════════
    private void showLogin() {
        root.setTop(null); root.setLeft(null); root.setRight(null); root.setBottom(null);
        root.setStyle(BG_GRADIENT_LOGIN);
        root.setCenter(buildLoginCard());
    }

    private VBox buildLoginCard() {
        VBox wrap = new VBox(); wrap.setAlignment(Pos.CENTER);

        VBox card = new VBox(18);
        card.setMaxWidth(420);
        card.setAlignment(Pos.CENTER);
        card.setStyle("-fx-background-color: white; -fx-background-radius: 20px; "
                    + "-fx-padding: 48px 44px; -fx-effect: dropshadow(gaussian,rgba(0,0,0,.25),30,0,0,8);");

        // Logo area
        Label logo = new Label("💹 Finance Pro");
        logo.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 34));
        logo.setStyle("-fx-text-fill: linear-gradient(to right,#6366f1,#0ea5e9); -fx-text-fill: #6366f1;");
        Label sub = new Label("Professional Finance Management");
        sub.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px;");

        TextField    tfUser = styledField("Username");
        PasswordField pfPass = styledPass("Password");

        Button btnLogin = new Button("Sign In");
        btnLogin.setMaxWidth(Double.MAX_VALUE);
        btnLogin.setStyle(BTN_PRIMARY + "-fx-font-size:15px; -fx-padding:13px;");

        Button btnReg = new Button("Create Account");
        btnReg.setMaxWidth(Double.MAX_VALUE);
        btnReg.setStyle("-fx-background-color: transparent; -fx-border-color: #6366f1; "
                      + "-fx-border-radius:8px; -fx-text-fill:#6366f1; -fx-font-size:13px; "
                      + "-fx-padding:11px; -fx-cursor:hand;");

        Label lbStatus = new Label(); lbStatus.setStyle("-fx-text-fill:#ef4444; -fx-font-size:12px;");
        Label lbDemo   = new Label("Demo: admin / admin123");
        lbDemo.setStyle("-fx-text-fill:#cbd5e1; -fx-font-size:11px;");

        btnLogin.setOnAction(e -> {
            if (tfUser.getText().isBlank() || pfPass.getText().isBlank()) {
                lbStatus.setText("Please enter username and password."); return;
            }
            lbStatus.setText("Authenticating…");
            new Thread(() -> authenticate(tfUser.getText(), pfPass.getText(), lbStatus)).start();
        });
        pfPass.setOnAction(btnLogin.getOnAction());
        btnReg.setOnAction(e -> showRegister());

        card.getChildren().addAll(logo, sub, new Separator(),
                                  tfUser, pfPass, btnLogin, btnReg, lbStatus, lbDemo);
        wrap.getChildren().add(card);
        return wrap;
    }

    private void authenticate(String user, String pass, Label lbStatus) {
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            PreparedStatement ps = c.prepareStatement(
                "SELECT id,username,full_name FROM users WHERE username=? AND password=?");
            ps.setString(1, user); ps.setString(2, pass);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                userId   = rs.getInt("id");
                username = rs.getString("username");
                fullName = rs.getString("full_name");
                Platform.runLater(() -> { buildDashboard(); loadAll(); });
            } else {
                Platform.runLater(() -> lbStatus.setText("Invalid username or password."));
            }
        } catch (SQLException e) {
            LOG.severe(e.getMessage());
            Platform.runLater(() -> lbStatus.setText("Database error. Try again."));
        }
    }

    private void showRegister() {
        VBox wrap = new VBox(); wrap.setAlignment(Pos.CENTER);
        root.setCenter(wrap);

        VBox card = new VBox(15); card.setMaxWidth(420); card.setAlignment(Pos.CENTER);
        card.setStyle("-fx-background-color: white; -fx-background-radius: 20px; "
                    + "-fx-padding: 44px; -fx-effect: dropshadow(gaussian,rgba(0,0,0,.2),24,0,0,6);");

        Label title = new Label("Create Account");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 28));
        title.setStyle("-fx-text-fill:#1e293b;");

        TextField    tfUser   = styledField("Username");
        TextField    tfName   = styledField("Full Name");
        PasswordField pfPass  = styledPass("Password");
        PasswordField pfConf  = styledPass("Confirm Password");
        Label lbMatch = new Label(); lbMatch.setStyle("-fx-font-size:11px;");
        pfPass.textProperty().addListener((o,v,n)->chkMatch(pfPass,pfConf,lbMatch));
        pfConf.textProperty().addListener((o,v,n)->chkMatch(pfPass,pfConf,lbMatch));

        Button btnReg  = new Button("Register"); btnReg.setMaxWidth(Double.MAX_VALUE);
        btnReg.setStyle(BTN_SUCCESS + "-fx-font-size:14px; -fx-padding:12px;");
        Button btnBack = new Button("← Back to Login"); btnBack.setStyle("-fx-background-color:transparent; -fx-text-fill:#6366f1; -fx-font-size:12px; -fx-cursor:hand;");
        Label  lbStat  = new Label(); lbStat.setStyle("-fx-text-fill:#ef4444; -fx-font-size:12px;");

        btnReg.setOnAction(e -> {
            if (tfUser.getText().isBlank() || pfPass.getText().isBlank()) { lbStat.setText("Username & password required."); return; }
            if (!pfPass.getText().equals(pfConf.getText())) { lbStat.setText("Passwords do not match."); return; }
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                PreparedStatement ps = c.prepareStatement("INSERT INTO users (username,password,full_name) VALUES (?,?,?)");
                ps.setString(1, tfUser.getText()); ps.setString(2, pfPass.getText()); ps.setString(3, tfName.getText());
                ps.executeUpdate();
                showLogin();
            } catch (SQLException ex) { lbStat.setText("Username already exists."); }
        });
        btnBack.setOnAction(e -> showLogin());

        card.getChildren().addAll(title, new Separator(), tfUser, tfName, pfPass, pfConf, lbMatch, btnReg, btnBack, lbStat);
        wrap.getChildren().add(card);
    }

    private void chkMatch(PasswordField p, PasswordField c, Label l) {
        if (p.getText().isEmpty() && c.getText().isEmpty()) { l.setText(""); return; }
        if (p.getText().equals(c.getText())) { l.setText("✓ Passwords match"); l.setStyle("-fx-text-fill:#16a34a; -fx-font-size:11px;"); }
        else { l.setText("✗ Passwords do not match"); l.setStyle("-fx-text-fill:#ef4444; -fx-font-size:11px;"); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MAIN SHELL
    // ══════════════════════════════════════════════════════════════════════════
    private void buildDashboard() {
        root.setStyle(BG_LIGHT);
        root.setTop(buildHeader());
        root.setLeft(buildNav());
        root.setRight(buildSidebar());
        root.setCenter(buildWorkspace());
    }

    // ── Header ────────────────────────────────────────────────────────────────
    private HBox buildHeader() {
        HBox bar = new HBox(12);
        bar.setPadding(new Insets(14, 22, 14, 22));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #0f172a; "
                   + "-fx-effect: dropshadow(gaussian,rgba(0,0,0,.35),14,0,0,3);");

        Label logo = new Label("💹 Finance Pro");
        logo.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 20));
        logo.setStyle("-fx-text-fill: #e2e8f0;");

        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);

        Button btnTx     = headerBtn("＋ Transaction", "#16a34a");
        Button btnBudget = headerBtn("📋 Set Budget",  "#2563eb");
        themeBtn         = headerBtn("🌙 Dark",        "#475569");
        Label  lbUser    = new Label("👤 " + fullName);
        lbUser.setStyle("-fx-text-fill:#94a3b8; -fx-font-size:13px;");
        Button btnOut    = headerBtn("⏻ Logout",       "#dc2626");

        btnTx.setOnAction(e     -> showCard("addTx"));
        btnBudget.setOnAction(e -> showCard("addBudget"));
        themeBtn.setOnAction(e  -> toggleTheme());
        btnOut.setOnAction(e    -> logout());

        bar.getChildren().addAll(logo, sp, btnTx, btnBudget, themeBtn, lbUser, btnOut);
        return bar;
    }

    private Button headerBtn(String text, String bg) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:" + bg + "; -fx-text-fill:white; "
                 + "-fx-background-radius:7px; -fx-padding:7px 14px; "
                 + "-fx-font-size:12px; -fx-cursor:hand;");
        return b;
    }

    // ── Left navigation ────────────────────────────────────────────────────────
    private VBox buildNav() {
        VBox nav = new VBox(4);
        nav.setPadding(new Insets(20, 10, 20, 10));
        nav.setPrefWidth(200);
        nav.setStyle("-fx-background-color: #1e293b;");

        Label lbSection = new Label("WORKSPACE");
        lbSection.setStyle("-fx-text-fill:#475569; -fx-font-size:10px; -fx-font-weight:bold; -fx-padding:0 0 8 6;");

        nav.getChildren().addAll(lbSection,
            navBtn("📊  Dashboard",       "dashboard"),
            navBtn("💰  Transactions",     "transactions"),
            navBtn("➕  Add Transaction",  "addTx"),
            navBtn("📋  Budgets",          "budgets"),
            navBtn("🎯  Set Budget",       "addBudget"));
        return nav;
    }

    private Button navBtn(String text, String key) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setStyle(NAV_IDLE);
        b.setOnAction(e -> showCard(key));
        navBtns.put(key, b);
        return b;
    }

    // ── Workspace (card stack) ────────────────────────────────────────────────
    private StackPane buildWorkspace() {
        cards.clear();
        workspace = new StackPane();
        workspace.setStyle("-fx-background-color: transparent;");

        // Each card is built once; labels are stored as fields so data updates in-place
        ScrollPane dashScroll = scrollWrap(buildDashboardCard());
        VBox       txPane     = buildTransactionsCard();
        VBox       addTxPane  = buildAddTxCard();
        VBox       budgetsPane= buildBudgetsCard();
        VBox       addBudget  = buildAddBudgetCard();

        cards.put("dashboard",    dashScroll);
        cards.put("transactions", txPane);
        cards.put("addTx",        addTxPane);
        cards.put("budgets",      budgetsPane);
        cards.put("addBudget",    addBudget);

        workspace.getChildren().addAll(cards.values());
        showCard("dashboard");
        return workspace;
    }

    private void showCard(String key) {
        if (workspace == null || !cards.containsKey(key)) return;
        cards.forEach((k, v) -> { v.setVisible(k.equals(key)); v.setManaged(k.equals(key)); });
        navBtns.forEach((k, b) -> b.setStyle(k.equals(key) ? NAV_ACTIVE : NAV_IDLE));
        switch (key) {
            case "dashboard"    -> loadAll();
            case "transactions" -> loadTransactions();
            case "budgets"      -> loadBudgets();
        }
    }

    // ── Sidebar ────────────────────────────────────────────────────────────────
    private VBox buildSidebar() {
        VBox bar = new VBox(14);
        bar.setPadding(new Insets(18));
        bar.setPrefWidth(270);
        bar.setStyle("-fx-background-color: #1e293b; "
                   + "-fx-effect: dropshadow(gaussian,rgba(0,0,0,.3),12,0,0,2);");

        Label lbTitle = new Label("🔔 Notifications");
        lbTitle.setStyle("-fx-text-fill:#e2e8f0; -fx-font-weight:bold; -fx-font-size:14px;");

        notifList = new ListView<>();
        notifList.setPrefHeight(300);
        notifList.setStyle("-fx-background-color:#0f172a; -fx-control-inner-background:#0f172a; "
                         + "-fx-text-fill:#94a3b8; -fx-font-size:11px; -fx-background-radius:8px;");

        Button btnClear = new Button("Clear All"); btnClear.setMaxWidth(Double.MAX_VALUE);
        btnClear.setStyle("-fx-background-color:#334155; -fx-text-fill:#94a3b8; "
                        + "-fx-cursor:hand; -fx-background-radius:6px; -fx-padding:7px;");
        btnClear.setOnAction(e -> notifList.getItems().clear());

        // Quick stats mini-card
        VBox statsCard = new VBox(8);
        statsCard.setStyle("-fx-background-color:#0f172a; -fx-background-radius:10px; -fx-padding:14px;");
        Label lbSt = new Label("📈 Quick Stats");
        lbSt.setStyle("-fx-text-fill:#7dd3fc; -fx-font-weight:bold; -fx-font-size:13px;");
        lbSideStats = new Label("Loading…");
        lbSideStats.setStyle("-fx-text-fill:#94a3b8; -fx-font-size:11px;");
        lbSideStats.setWrapText(true);
        statsCard.getChildren().addAll(lbSt, lbSideStats);

        bar.getChildren().addAll(lbTitle, notifList, btnClear, statsCard);
        return bar;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DASHBOARD  (eye-catching redesign)
    // ══════════════════════════════════════════════════════════════════════════
    private VBox buildDashboardCard() {
        VBox root = new VBox(24);
        root.setPadding(new Insets(28));

        // ── Row 0: greeting banner ─────────────────────────────────────────
        HBox banner = new HBox();
        banner.setStyle("-fx-background-color: linear-gradient(to right, #6366f1, #0ea5e9);"
                      + "-fx-background-radius:16px; -fx-padding:22px 28px;");
        banner.setAlignment(Pos.CENTER_LEFT);

        VBox bannerText = new VBox(4);
        Label lbGreet = new Label("Welcome back, " + (fullName.isBlank() ? username : fullName) + " 👋");
        lbGreet.setStyle("-fx-text-fill:white; -fx-font-size:20px; -fx-font-weight:bold;");
        Label lbDate = new Label(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")));
        lbDate.setStyle("-fx-text-fill:rgba(255,255,255,.75); -fx-font-size:13px;");
        bannerText.getChildren().addAll(lbGreet, lbDate);
        HBox.setHgrow(bannerText, Priority.ALWAYS);

        Label lbEmoji = new Label("💹"); lbEmoji.setStyle("-fx-font-size:52px;");
        banner.getChildren().addAll(bannerText, lbEmoji);

        // ── Row 1: KPI cards (3 big) ───────────────────────────────────────
        lbTotalIncome  = kpiValue("#22c55e");
        lbTotalExpense = kpiValue("#ef4444");
        lbBalance      = kpiValue("#38bdf8");

        HBox kpiRow = new HBox(18);
        kpiRow.getChildren().addAll(
            kpiCard("💰 Total Income",    lbTotalIncome,  "#22c55e", "#dcfce7", "↑"),
            kpiCard("💸 Total Expenses",  lbTotalExpense, "#ef4444", "#fee2e2", "↓"),
            kpiCard("⚖️ Net Balance",     lbBalance,      "#38bdf8", "#e0f2fe", "→"));
        kpiRow.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        // ── Row 2: secondary KPI (4 smaller) ──────────────────────────────
        lbTodayIncome   = miniVal("#22c55e");
        lbTodayExpense  = miniVal("#ef4444");
        lbMonthIncome   = miniVal("#22c55e");
        lbMonthExpense  = miniVal("#ef4444");

        HBox miniRow = new HBox(14);
        miniRow.getChildren().addAll(
            miniCard("📅 Today Income",     lbTodayIncome,   "#22c55e"),
            miniCard("📅 Today Expense",    lbTodayExpense,  "#ef4444"),
            miniCard("📆 Month Income",     lbMonthIncome,   "#22c55e"),
            miniCard("📆 Month Expense",    lbMonthExpense,  "#ef4444"));
        miniRow.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        // ── Row 3: insight cards (4) ───────────────────────────────────────
        lbBudgetHealth = miniVal("#6366f1");
        lbSavingsRate  = miniVal("#22c55e");
        lbCashFlow     = miniVal("#0ea5e9");
        lbTopCategory  = miniVal("#f59e0b");

        HBox insightRow = new HBox(14);
        insightRow.getChildren().addAll(
            miniCard("🎯 Budget Health",   lbBudgetHealth, "#6366f1"),
            miniCard("💡 Savings Rate",    lbSavingsRate,  "#22c55e"),
            miniCard("🌊 Monthly Cash Flow",lbCashFlow,    "#0ea5e9"),
            miniCard("🔥 Top Spend",       lbTopCategory,  "#f59e0b"));
        insightRow.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        // ── Row 4: charts side-by-side ─────────────────────────────────────
        // Pie chart
        pieChart = new PieChart();
        pieChart.setTitle("Expense Breakdown");
        pieChart.setPrefSize(420, 340);
        pieChart.setStyle(CHART_STYLE);
        pieChart.setLegendVisible(true);

        // Bar chart
        barX = new CategoryAxis(); barY = new NumberAxis();
        barX.setLabel("Month"); barY.setLabel("Amount ($)");
        barChart = new BarChart<>(barX, barY);
        barChart.setTitle("6-Month Income vs Expense");
        barChart.setPrefSize(560, 340);
        barChart.setStyle(CHART_STYLE);
        barChart.setAnimated(false);

        HBox charts = new HBox(20, pieChart, barChart);
        charts.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(barChart, Priority.ALWAYS);

        root.getChildren().addAll(banner, kpiRow, miniRow, insightRow, charts);
        return root;
    }

    // ─── Dashboard helpers ─────────────────────────────────────────────────────
    private VBox kpiCard(String title, Label valLabel, String accent, String bg, String icon) {
        VBox card = new VBox(10);
        card.setPadding(new Insets(22));
        String base = "-fx-background-color:" + bg + "; -fx-background-radius:16px; "
                    + "-fx-border-color:" + accent + "55; -fx-border-radius:16px; -fx-border-width:1.5px; "
                    + "-fx-effect: dropshadow(gaussian,rgba(0,0,0,.08),14,0,0,3);";
        String hover= "-fx-background-color:" + bg + "; -fx-background-radius:16px; "
                    + "-fx-border-color:" + accent + "; -fx-border-radius:16px; -fx-border-width:2px; "
                    + "-fx-effect: dropshadow(gaussian," + accent + "55,22,0,0,5);";
        card.setStyle(base);
        card.setOnMouseEntered(e -> card.setStyle(hover));
        card.setOnMouseExited(e  -> card.setStyle(base));

        HBox top = new HBox(8);
        Label icn = new Label(icon);
        icn.setStyle("-fx-background-color:" + accent + "22; -fx-background-radius:8px; "
                   + "-fx-text-fill:" + accent + "; -fx-font-size:22px; -fx-padding:6px 10px;");
        VBox txt = new VBox(2);
        Label lbT = new Label(title);
        lbT.setStyle("-fx-text-fill:#64748b; -fx-font-size:13px; -fx-font-weight:bold;");
        valLabel.setStyle("-fx-text-fill:" + accent + "; -fx-font-size:28px; -fx-font-weight:bold;");
        valLabel.setText("$0.00");
        txt.getChildren().addAll(lbT, valLabel);
        top.getChildren().addAll(icn, txt);
        card.getChildren().add(top);
        return card;
    }

    private VBox miniCard(String title, Label valLabel, String accent) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(16));
        card.setStyle("-fx-background-color: white; -fx-background-radius:12px; "
                    + "-fx-border-color:" + accent + "44; -fx-border-radius:12px; -fx-border-width:1.5px; "
                    + "-fx-effect: dropshadow(gaussian,rgba(0,0,0,.06),10,0,0,2);");
        card.setAlignment(Pos.CENTER_LEFT);

        // Colored top bar
        Rectangle bar = new Rectangle(36, 4);
        bar.setArcWidth(4); bar.setArcHeight(4);
        bar.setFill(Color.web(accent));

        Label lbT = new Label(title);
        lbT.setStyle("-fx-text-fill:#94a3b8; -fx-font-size:11px; -fx-font-weight:bold;");
        valLabel.setText("$0");
        card.getChildren().addAll(bar, lbT, valLabel);
        return card;
    }

    private Label kpiValue(String color) {
        Label l = new Label("$0.00");
        l.setStyle("-fx-text-fill:" + color + "; -fx-font-size:28px; -fx-font-weight:bold;");
        return l;
    }

    private Label miniVal(String color) {
        Label l = new Label("$0");
        l.setStyle("-fx-text-fill:" + color + "; -fx-font-size:17px; -fx-font-weight:bold;");
        return l;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TRANSACTIONS
    // ══════════════════════════════════════════════════════════════════════════
    private VBox buildTransactionsCard() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(24));

        Label title = sectionTitle("💰 Transactions");

        // Filter bar
        HBox filters = new HBox(10);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.setStyle("-fx-background-color:white; -fx-background-radius:12px; "
                       + "-fx-padding:14px 18px; -fx-border-color:#e2e8f0; "
                       + "-fx-border-radius:12px; -fx-border-width:1px; "
                       + "-fx-effect: dropshadow(gaussian,rgba(0,0,0,.05),8,0,0,2);");

        txSearch = new TextField();
        txSearch.setPromptText("🔍  Search…");
        txSearch.setPrefWidth(190);
        txSearch.setStyle("-fx-font-size:13px; -fx-padding:8px; -fx-background-radius:7px;");
        txSearch.textProperty().addListener((o, v, n) -> loadTransactions());

        txCatFilter = new ComboBox<>();
        txCatFilter.getItems().addAll("All", "Salary", "Freelance", "Investment",
            "Food & Dining", "Transportation", "Shopping", "Entertainment",
            "Healthcare", "Utilities", "Rent", "Education", "Other Expense", "Other Income");
        txCatFilter.setValue("All");
        txCatFilter.setStyle("-fx-font-size:13px;");
        txCatFilter.setOnAction(e -> loadTransactions());

        txFromDate = new DatePicker(LocalDate.now().withDayOfMonth(1));
        txToDate   = new DatePicker(LocalDate.now());
        txFromDate.setStyle("-fx-font-size:12px;"); txToDate.setStyle("-fx-font-size:12px;");
        txFromDate.setOnAction(e -> loadTransactions());
        txToDate.setOnAction(e   -> loadTransactions());

        Button btnAdd = new Button("＋ Add Transaction");
        btnAdd.setStyle(BTN_SUCCESS + "-fx-font-size:13px; -fx-padding:8px 14px;");
        btnAdd.setOnAction(e -> showCard("addTx"));

        filters.getChildren().addAll(
            txSearch, txCatFilter,
            new Label("From:"), txFromDate,
            new Label("To:"),   txToDate,
            new Region() {{ HBox.setHgrow(this, Priority.ALWAYS); }},
            btnAdd);

        // Table
        txData  = FXCollections.observableArrayList();
        txTable = new TableView<>(txData);
        txTable.setStyle("-fx-font-size:13px; -fx-background-radius:10px;");
        txTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        txTable.setPlaceholder(new Label("No transactions found."));

        txTable.getColumns().addAll(
            txCol("ID",          70,  t -> String.valueOf(t.id)),
            txTypeCol(),
            txCol("Category",    160, t -> t.category),
            txAmtCol(),
            txCol("Description", 260, t -> t.description),
            txDateCol(),
            txDeleteCol()
        );

        VBox.setVgrow(txTable, Priority.ALWAYS);
        root.getChildren().addAll(title, filters, txTable);
        return root;
    }

    @SuppressWarnings("unchecked")
    private TableColumn<Transaction, String> txCol(String hdr, int w, java.util.function.Function<Transaction,String> fn) {
        TableColumn<Transaction, String> col = new TableColumn<>(hdr);
        col.setPrefWidth(w);
        col.setCellValueFactory(d -> new SimpleStringProperty(fn.apply(d.getValue())));
        return col;
    }

    private TableColumn<Transaction, String> txTypeCol() {
        TableColumn<Transaction, String> col = new TableColumn<>("Type");
        col.setPrefWidth(110);
        col.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().type));
        col.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean e) {
                super.updateItem(v, e);
                if (e || v == null) { setText(null); setStyle(""); return; }
                if ("INCOME".equals(v)) { setText("📈 INCOME"); setStyle("-fx-text-fill:#16a34a; -fx-font-weight:bold;"); }
                else                   { setText("📉 EXPENSE"); setStyle("-fx-text-fill:#dc2626; -fx-font-weight:bold;"); }
            }
        });
        return col;
    }

    private TableColumn<Transaction, Double> txAmtCol() {
        TableColumn<Transaction, Double> col = new TableColumn<>("Amount");
        col.setPrefWidth(120);
        col.setCellValueFactory(d -> new SimpleDoubleProperty(d.getValue().amount).asObject());
        col.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean e) {
                super.updateItem(v, e);
                if (e || v == null) { setText(null); return; }
                setText(String.format("$%,.2f", v));
                Transaction t = getTableView().getItems().get(getIndex());
                setStyle("INCOME".equals(t.type)
                    ? "-fx-text-fill:#16a34a; -fx-font-weight:bold;"
                    : "-fx-text-fill:#dc2626; -fx-font-weight:bold;");
            }
        });
        return col;
    }

    private TableColumn<Transaction, LocalDate> txDateCol() {
        TableColumn<Transaction, LocalDate> col = new TableColumn<>("Date");
        col.setPrefWidth(120);
        col.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().txDate));
        col.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(LocalDate v, boolean e) {
                super.updateItem(v, e);
                setText(e || v == null ? null : v.format(DateTimeFormatter.ofPattern("MMM dd, yyyy")));
            }
        });
        return col;
    }

    private TableColumn<Transaction, Void> txDeleteCol() {
        TableColumn<Transaction, Void> col = new TableColumn<>("Action");
        col.setPrefWidth(90);
        col.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button("🗑 Delete") {{
                setStyle("-fx-background-color:#ef4444; -fx-text-fill:white; "
                       + "-fx-cursor:hand; -fx-background-radius:6px; -fx-padding:4px 9px;");
                setOnAction(e -> deleteTransaction(getTableView().getItems().get(getIndex())));
            }};
            @Override protected void updateItem(Void v, boolean e) {
                super.updateItem(v, e); setGraphic(e ? null : btn);
            }
        });
        return col;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ADD TRANSACTION FORM
    // ══════════════════════════════════════════════════════════════════════════
    private VBox buildAddTxCard() {
        VBox outer = new VBox(20);
        outer.setPadding(new Insets(30));
        outer.setAlignment(Pos.TOP_CENTER);

        VBox card = formCard("➕ Add Transaction",
                             "Record income or expense quickly.");

        GridPane grid = formGrid();

        ComboBox<String> cmbType = new ComboBox<>();
        cmbType.getItems().addAll("INCOME", "EXPENSE"); cmbType.setValue("EXPENSE");
        cmbType.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> cmbCat = new ComboBox<>();
        cmbCat.setMaxWidth(Double.MAX_VALUE);
        updateCats(cmbType.getValue(), cmbCat);
        cmbType.setOnAction(e -> updateCats(cmbType.getValue(), cmbCat));

        TextField  tfAmt  = new TextField(); tfAmt.setPromptText("0.00");
        TextArea   taDesc = new TextArea(); taDesc.setPromptText("Description (optional)"); taDesc.setPrefRowCount(3);
        DatePicker dp     = new DatePicker(LocalDate.now()); dp.setMaxWidth(Double.MAX_VALUE);

        addRow(grid, "Type",         cmbType, 0);
        addRow(grid, "Category",     cmbCat,  1);
        addRow(grid, "Amount ($)",   tfAmt,   2);
        addRow(grid, "Description",  taDesc,  3);
        addRow(grid, "Date",         dp,      4);

        Label lbStat = new Label(); lbStat.setStyle("-fx-text-fill:#ef4444;");

        Button btnSave = new Button("💾 Save Transaction"); btnSave.setStyle(BTN_PRIMARY + "-fx-padding:10px 20px;");
        Button btnBack = new Button("← Back");              btnBack.setStyle(BTN_GRAY   + "-fx-padding:10px 16px;");
        HBox actions = new HBox(12, btnBack, btnSave); actions.setAlignment(Pos.CENTER_RIGHT);

        btnSave.setOnAction(e -> {
            try {
                double amt = Double.parseDouble(tfAmt.getText().trim());
                if (amt <= 0 || cmbCat.getValue() == null || dp.getValue() == null) throw new NumberFormatException();
                saveTx(new Transaction(0, userId, cmbType.getValue(), cmbCat.getValue(),
                                       amt, taDesc.getText(), dp.getValue()));
                tfAmt.clear(); taDesc.clear(); dp.setValue(LocalDate.now()); lbStat.setText("");
                showCard("transactions");
            } catch (NumberFormatException ex) {
                lbStat.setText("⚠  Please enter a valid positive amount.");
            }
        });
        btnBack.setOnAction(e -> showCard("transactions"));

        card.getChildren().addAll(grid, lbStat, actions);
        outer.getChildren().add(card);
        return outer;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BUDGETS  (fixed: real progress bars, live spent data)
    // ══════════════════════════════════════════════════════════════════════════
    private VBox buildBudgetsCard() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(24));

        HBox hdr = new HBox(12);
        hdr.setAlignment(Pos.CENTER_LEFT);
        Label title = sectionTitle("🎯 Monthly Budgets — " + YearMonth.now().format(DateTimeFormatter.ofPattern("MMMM yyyy")));
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        Button btnAdd = new Button("＋ Set Budget"); btnAdd.setStyle(BTN_PRIMARY + "-fx-padding:8px 16px;");
        btnAdd.setOnAction(e -> showCard("addBudget"));
        hdr.getChildren().addAll(title, sp, btnAdd);

        budgetData  = FXCollections.observableArrayList();
        budgetTable = new TableView<>(budgetData);
        budgetTable.setStyle("-fx-font-size:13px;");
        budgetTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        budgetTable.setPlaceholder(new Label("No budgets set. Click '+ Set Budget' to create one."));

        TableColumn<Budget, String> colCat = new TableColumn<>("Category");
        colCat.setPrefWidth(180);
        colCat.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().category));

        TableColumn<Budget, Double> colLimit = new TableColumn<>("Budget");
        colLimit.setPrefWidth(120);
        colLimit.setCellValueFactory(d -> new SimpleDoubleProperty(d.getValue().amount).asObject());
        colLimit.setCellFactory(c -> coloredMoneyCell("#2563eb"));

        TableColumn<Budget, Double> colSpent = new TableColumn<>("Spent");
        colSpent.setPrefWidth(120);
        colSpent.setCellValueFactory(d -> new SimpleDoubleProperty(d.getValue().spent).asObject());
        colSpent.setCellFactory(c -> coloredMoneyCell("#dc2626"));

        TableColumn<Budget, Double> colRemain = new TableColumn<>("Remaining");
        colRemain.setPrefWidth(120);
        colRemain.setCellValueFactory(d -> new SimpleDoubleProperty(d.getValue().remaining()).asObject());
        colRemain.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean e) {
                super.updateItem(v, e);
                if (e || v == null) { setText(null); setStyle(""); return; }
                setText(String.format("$%,.2f", v));
                setStyle(v < 0 ? "-fx-text-fill:#dc2626; -fx-font-weight:bold;"
                               : "-fx-text-fill:#16a34a; -fx-font-weight:bold;");
            }
        });

        // Progress bar column — the KEY missing piece
        TableColumn<Budget, Budget> colProgress = new TableColumn<>("Usage");
        colProgress.setPrefWidth(200);
        colProgress.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        colProgress.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Budget b, boolean e) {
                super.updateItem(b, e);
                if (e || b == null) { setGraphic(null); return; }
                double pct = b.amount <= 0 ? 0 : Math.min(b.spent / b.amount, 1.0);
                ProgressBar pb = new ProgressBar(pct);
                pb.setPrefWidth(140);
                String col = pct >= 1.0 ? "#ef4444" : pct >= 0.8 ? "#f59e0b" : "#22c55e";
                pb.setStyle("-fx-accent:" + col + "; -fx-background-radius:5px;");
                Label lbl = new Label(String.format(" %.0f%%", pct * 100));
                lbl.setStyle("-fx-font-size:11px; -fx-font-weight:bold; -fx-text-fill:" + col + ";");
                HBox box = new HBox(4, pb, lbl); box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });

        TableColumn<Budget, String> colStatus = new TableColumn<>("Status");
        colStatus.setPrefWidth(200);
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status()));
        colStatus.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean e) {
                super.updateItem(v, e);
                if (e || v == null) { setText(null); setStyle(""); return; }
                setText(v);
                setStyle(v.startsWith("Over") ? "-fx-text-fill:#dc2626; -fx-font-weight:bold;"
                       : v.contains("watch")  ? "-fx-text-fill:#f59e0b; -fx-font-weight:bold;"
                       : "-fx-text-fill:#16a34a;");
            }
        });

        TableColumn<Budget, Void> colDel = new TableColumn<>("Action");
        colDel.setPrefWidth(90);
        colDel.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button("🗑") {{
                setStyle("-fx-background-color:#ef4444; -fx-text-fill:white; "
                       + "-fx-cursor:hand; -fx-background-radius:6px; -fx-padding:4px 10px;");
                setOnAction(e -> deleteBudget(getTableView().getItems().get(getIndex())));
            }};
            @Override protected void updateItem(Void v, boolean e) {
                super.updateItem(v, e); setGraphic(e ? null : btn);
            }
        });

        budgetTable.getColumns().addAll(colCat, colLimit, colSpent, colRemain, colProgress, colStatus, colDel);
        VBox.setVgrow(budgetTable, Priority.ALWAYS);
        root.getChildren().addAll(hdr, budgetTable);
        return root;
    }

    private TableCell<Budget, Double> coloredMoneyCell(String color) {
        return new TableCell<>() {
            @Override protected void updateItem(Double v, boolean e) {
                super.updateItem(v, e);
                if (e || v == null) { setText(null); return; }
                setText(String.format("$%,.2f", v));
                setStyle("-fx-text-fill:" + color + "; -fx-font-weight:bold;");
            }
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ADD BUDGET FORM  (fixed: properly saves & refreshes)
    // ══════════════════════════════════════════════════════════════════════════
    private VBox buildAddBudgetCard() {
        VBox outer = new VBox(20);
        outer.setPadding(new Insets(30));
        outer.setAlignment(Pos.TOP_CENTER);

        VBox card = formCard("🎯 Set Monthly Budget",
                             "Create or update a spending limit for a category this month.");

        GridPane grid = formGrid();

        ComboBox<String> cmbCat = new ComboBox<>();
        cmbCat.getItems().addAll("Food & Dining", "Transportation", "Shopping",
                                  "Entertainment", "Healthcare", "Utilities",
                                  "Rent", "Education", "Other Expense");
        cmbCat.setValue("Food & Dining"); cmbCat.setMaxWidth(Double.MAX_VALUE);

        TextField  tfAmt  = new TextField(); tfAmt.setPromptText("Monthly limit (e.g. 500)");
        TextField  tfMon  = new TextField(YearMonth.now().toString());
        tfMon.setPromptText("YYYY-MM");

        addRow(grid, "Category",  cmbCat, 0);
        addRow(grid, "Amount ($)", tfAmt, 1);
        addRow(grid, "Month",     tfMon,  2);

        Label lbStat = new Label(); lbStat.setStyle("-fx-text-fill:#ef4444;");

        Button btnSave = new Button("💾 Save Budget"); btnSave.setStyle(BTN_PRIMARY + "-fx-padding:10px 20px;");
        Button btnBack = new Button("← Back");         btnBack.setStyle(BTN_GRAY   + "-fx-padding:10px 16px;");
        HBox actions = new HBox(12, btnBack, btnSave); actions.setAlignment(Pos.CENTER_RIGHT);

        btnSave.setOnAction(e -> {
            try {
                String monStr = tfMon.getText().trim();
                double amt    = Double.parseDouble(tfAmt.getText().trim());
                YearMonth.parse(monStr); // validates format
                if (amt <= 0) throw new NumberFormatException("non-positive");
                saveBudget(cmbCat.getValue(), amt, monStr);
                tfAmt.clear();
                lbStat.setStyle("-fx-text-fill:#16a34a;");
                lbStat.setText("✓ Budget saved for " + cmbCat.getValue() + " (" + monStr + ")");
                // Navigate to budgets after short delay so user sees success msg
                new Thread(() -> {
                    try { Thread.sleep(900); } catch (InterruptedException ignored) {}
                    Platform.runLater(() -> showCard("budgets"));
                }).start();
            } catch (Exception ex) {
                lbStat.setStyle("-fx-text-fill:#ef4444;");
                lbStat.setText("⚠  Enter a positive amount and a valid month (YYYY-MM).");
            }
        });
        btnBack.setOnAction(e -> showCard("budgets"));

        card.getChildren().addAll(grid, lbStat, actions);
        outer.getChildren().add(card);
        return outer;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DATA LOADING  (all async, Platform.runLater for UI)
    // ══════════════════════════════════════════════════════════════════════════
    private void loadAll() {
        loadSummaryStats();
        loadQuickStats();
        loadBudgetOverview();
        loadPieChart();
        loadBarChart();
        loadTransactions();
        loadBudgets();
    }

    // ── All-time totals ────────────────────────────────────────────────────────
    private void loadSummaryStats() {
        bg(() -> {
            try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                    "SELECT SUM(CASE WHEN type='INCOME' THEN amount ELSE 0 END) income,"
                  + "       SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) expense "
                  + "FROM transactions WHERE user_id=?")) {
                ps.setInt(1, userId); ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    double inc = rs.getDouble("income"), exp = rs.getDouble("expense"), bal = inc - exp;
                    ui(() -> {
                        lbTotalIncome .setText(fmt(inc));
                        lbTotalExpense.setText(fmt(exp));
                        lbBalance     .setText(fmt(bal));
                        lbBalance.setStyle(bal >= 0
                            ? "-fx-text-fill:#22c55e; -fx-font-size:28px; -fx-font-weight:bold;"
                            : "-fx-text-fill:#ef4444; -fx-font-size:28px; -fx-font-weight:bold;");
                    });
                }
            } catch (SQLException e) { LOG.severe(e.getMessage()); }
        });
    }

    // ── Today + month quick stats ──────────────────────────────────────────────
    private void loadQuickStats() {
        bg(() -> {
            try (Connection c = conn()) {
                // Today
                PreparedStatement ps = c.prepareStatement(
                    "SELECT SUM(CASE WHEN type='INCOME' THEN amount ELSE 0 END) ti,"
                  + "       SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) te "
                  + "FROM transactions WHERE user_id=? AND transaction_date=CURDATE()");
                ps.setInt(1, userId); ResultSet rs = ps.executeQuery();
                double todayI = 0, todayE = 0;
                if (rs.next()) { todayI = rs.getDouble("ti"); todayE = rs.getDouble("te"); }

                // Month
                ps = c.prepareStatement(
                    "SELECT SUM(CASE WHEN type='INCOME' THEN amount ELSE 0 END) mi,"
                  + "       SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) me "
                  + "FROM transactions WHERE user_id=? "
                  + "AND MONTH(transaction_date)=MONTH(CURDATE()) AND YEAR(transaction_date)=YEAR(CURDATE())");
                ps.setInt(1, userId); rs = ps.executeQuery();
                double monI = 0, monE = 0;
                if (rs.next()) { monI = rs.getDouble("mi"); monE = rs.getDouble("me"); }

                final double fi=todayI, fe=todayE, mi=monI, me=monE;
                ui(() -> {
                    lbTodayIncome .setText(fmt(fi));
                    lbTodayExpense.setText(fmt(fe));
                    lbMonthIncome .setText(fmt(mi));
                    lbMonthExpense.setText(fmt(me));
                    if (lbSideStats != null)
                        lbSideStats.setText(String.format(
                            "Today:  +%s  -%s%nMonth:  +%s  -%s%nNet:  %s",
                            fmt(fi), fmt(fe), fmt(mi), fmt(me), fmt(mi-me)));
                });
            } catch (SQLException e) { LOG.severe(e.getMessage()); }
        });
    }

    // ── Budget overview for insight cards ─────────────────────────────────────
    private void loadBudgetOverview() {
        if (lbBudgetHealth == null) return;
        String mon = YearMonth.now().toString();
        bg(() -> {
            try (Connection c = conn()) {
                // Budget health: sum budgets vs spent (only budgeted categories)
                PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(b.amount),0) bt, "
                  + "       COALESCE(SUM(CASE WHEN t.type='EXPENSE' THEN t.amount ELSE 0 END),0) st "
                  + "FROM budgets b "
                  + "LEFT JOIN transactions t ON t.user_id=b.user_id AND t.category=b.category "
                  + "  AND t.type='EXPENSE' AND DATE_FORMAT(t.transaction_date,'%Y-%m')=b.month_year "
                  + "WHERE b.user_id=? AND b.month_year=?");
                ps.setInt(1, userId); ps.setString(2, mon);
                ResultSet rs = ps.executeQuery();
                double bt = 0, st = 0;
                if (rs.next()) { bt = rs.getDouble("bt"); st = rs.getDouble("st"); }

                // Month income & expense
                ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(CASE WHEN type='INCOME' THEN amount ELSE 0 END),0) inc,"
                  + "       COALESCE(SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END),0) exp "
                  + "FROM transactions WHERE user_id=? AND DATE_FORMAT(transaction_date,'%Y-%m')=?");
                ps.setInt(1, userId); ps.setString(2, mon); rs = ps.executeQuery();
                double inc = 0, exp = 0;
                if (rs.next()) { inc = rs.getDouble("inc"); exp = rs.getDouble("exp"); }

                // Top category
                ps = c.prepareStatement(
                    "SELECT category, SUM(amount) total FROM transactions "
                  + "WHERE user_id=? AND type='EXPENSE' AND DATE_FORMAT(transaction_date,'%Y-%m')=? "
                  + "GROUP BY category ORDER BY total DESC LIMIT 1");
                ps.setInt(1, userId); ps.setString(2, mon); rs = ps.executeQuery();
                String topCat = "—";
                if (rs.next()) topCat = rs.getString("category") + " " + fmt(rs.getDouble("total"));

                final double fbt=bt, fst=st, finc=inc, fexp=exp;
                final String ftop = topCat;
                ui(() -> {
                    lbBudgetHealth.setText(fbt <= 0 ? "No budgets"
                        : String.format("%.0f%% used", (fst/fbt)*100));
                    lbBudgetHealth.setStyle((fbt>0 && fst>fbt)
                        ? "-fx-text-fill:#ef4444; -fx-font-size:17px; -fx-font-weight:bold;"
                        : "-fx-text-fill:#6366f1; -fx-font-size:17px; -fx-font-weight:bold;");
                    double sr = finc <= 0 ? 0 : ((finc-fexp)/finc)*100;
                    lbSavingsRate.setText(String.format("%.0f%%", sr));
                    lbCashFlow   .setText(fmt(finc - fexp));
                    lbTopCategory.setText(ftop);
                });
            } catch (SQLException e) { LOG.severe(e.getMessage()); }
        });
    }

    // ── Pie chart ─────────────────────────────────────────────────────────────
    private void loadPieChart() {
        if (pieChart == null) return;
        bg(() -> {
            try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                    "SELECT category, SUM(amount) total FROM transactions "
                  + "WHERE user_id=? AND type='EXPENSE' GROUP BY category ORDER BY total DESC LIMIT 10")) {
                ps.setInt(1, userId); ResultSet rs = ps.executeQuery();
                ObservableList<PieChart.Data> data = FXCollections.observableArrayList();
                while (rs.next())
                    data.add(new PieChart.Data(
                        rs.getString("category") + " (" + fmt(rs.getDouble("total")) + ")",
                        rs.getDouble("total")));
                ui(() -> {
                    pieChart.setData(data.isEmpty()
                        ? FXCollections.observableArrayList(new PieChart.Data("No Data", 1)) : data);
                });
            } catch (SQLException e) { LOG.severe(e.getMessage()); }
        });
    }

    // ── Bar chart ─────────────────────────────────────────────────────────────
    private void loadBarChart() {
        if (barChart == null) return;
        bg(() -> {
            try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                    "SELECT DATE_FORMAT(transaction_date,'%Y-%m') mon, "
                  + "SUM(CASE WHEN type='INCOME' THEN amount ELSE 0 END) inc, "
                  + "SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) exp "
                  + "FROM transactions WHERE user_id=? "
                  + "GROUP BY mon ORDER BY mon DESC LIMIT 6")) {
                ps.setInt(1, userId); ResultSet rs = ps.executeQuery();
                XYChart.Series<String,Number> sInc = new XYChart.Series<>(); sInc.setName("Income");
                XYChart.Series<String,Number> sExp = new XYChart.Series<>(); sExp.setName("Expense");
                List<String[]> rows = new ArrayList<>();
                while (rs.next())
                    rows.add(new String[]{rs.getString("mon"),
                        String.valueOf(rs.getDouble("inc")), String.valueOf(rs.getDouble("exp"))});
                Collections.reverse(rows);
                for (String[] r : rows) {
                    String label = YearMonth.parse(r[0]).format(DateTimeFormatter.ofPattern("MMM yy"));
                    sInc.getData().add(new XYChart.Data<>(label, Double.parseDouble(r[1])));
                    sExp.getData().add(new XYChart.Data<>(label, Double.parseDouble(r[2])));
                }
                ui(() -> { barChart.getData().clear(); barChart.getData().addAll(sInc, sExp); });
            } catch (SQLException e) { LOG.severe(e.getMessage()); }
        });
    }

    // ── Transactions ──────────────────────────────────────────────────────────
    private void loadTransactions() {
        if (txTable == null) return;
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM transactions WHERE user_id=?");
        List<Object> params = new ArrayList<>(); params.add(userId);

        if (txCatFilter != null && !"All".equals(txCatFilter.getValue())) {
            sql.append(" AND category=?"); params.add(txCatFilter.getValue());
        }
        if (txFromDate != null && txFromDate.getValue() != null) {
            sql.append(" AND transaction_date>=?"); params.add(java.sql.Date.valueOf(txFromDate.getValue()));
        }
        if (txToDate != null && txToDate.getValue() != null) {
            sql.append(" AND transaction_date<=?"); params.add(java.sql.Date.valueOf(txToDate.getValue()));
        }
        if (txSearch != null && !txSearch.getText().isBlank()) {
            sql.append(" AND (description LIKE ? OR category LIKE ?)");
            String p = "%" + txSearch.getText() + "%"; params.add(p); params.add(p);
        }
        sql.append(" ORDER BY transaction_date DESC");

        bg(() -> {
            try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i+1, params.get(i));
                ResultSet rs = ps.executeQuery();
                ObservableList<Transaction> list = FXCollections.observableArrayList();
                while (rs.next())
                    list.add(new Transaction(rs.getInt("id"), rs.getInt("user_id"),
                        rs.getString("type"), rs.getString("category"), rs.getDouble("amount"),
                        rs.getString("description"), rs.getDate("transaction_date").toLocalDate()));
                ui(() -> { if (txData != null) { txData.clear(); txData.addAll(list); } });
            } catch (SQLException e) { LOG.severe(e.getMessage()); }
        });
    }

    // ── Budgets  ───────────────────────────────────────────────────────────────
    // FIX: The original query had a logic bug — it would double-count transactions
    // across multiple budgets. Fixed by computing spent per-budget separately.
    private void loadBudgets() {
        if (budgetTable == null || budgetData == null) return;
        bg(() -> {
            try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                    "SELECT b.id, b.category, b.amount, b.month_year, "
                  + "  COALESCE(("
                  + "    SELECT SUM(t.amount) FROM transactions t "
                  + "    WHERE t.user_id=b.user_id AND t.category=b.category "
                  + "      AND t.type='EXPENSE' AND DATE_FORMAT(t.transaction_date,'%Y-%m')=b.month_year"
                  + "  ),0) AS spent "
                  + "FROM budgets b "
                  + "WHERE b.user_id=? AND b.month_year=? "
                  + "ORDER BY b.category")) {
                ps.setInt(1, userId); ps.setString(2, YearMonth.now().toString());
                ResultSet rs = ps.executeQuery();
                ObservableList<Budget> list = FXCollections.observableArrayList();
                while (rs.next())
                    list.add(new Budget(rs.getInt("id"), userId,
                        rs.getString("category"), rs.getDouble("amount"),
                        rs.getString("month_year"), rs.getDouble("spent")));
                ui(() -> { budgetData.clear(); budgetData.addAll(list); });
            } catch (SQLException e) { LOG.severe(e.getMessage()); }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DB WRITE OPERATIONS
    // ══════════════════════════════════════════════════════════════════════════
    private void saveTx(Transaction t) {
        bg(() -> {
            try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions (user_id,type,category,amount,description,transaction_date)"
                  + " VALUES (?,?,?,?,?,?)")) {
                ps.setInt(1, t.userId); ps.setString(2, t.type); ps.setString(3, t.category);
                ps.setDouble(4, t.amount); ps.setString(5, t.description);
                ps.setDate(6, java.sql.Date.valueOf(t.txDate));
                ps.executeUpdate();
                ui(() -> { loadAll(); notify("✅ Transaction saved: " + t.category + " " + fmt(t.amount)); });
            } catch (SQLException e) {
                LOG.severe(e.getMessage());
                ui(() -> alert(AlertType.ERROR, "Error", "Failed to save transaction."));
            }
        });
    }

    private void saveBudget(String category, double amount, String monthYear) {
        bg(() -> {
            try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO budgets (user_id,category,amount,month_year) VALUES (?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE amount=VALUES(amount), updated_at=NOW()")) {
                ps.setInt(1, userId); ps.setString(2, category);
                ps.setDouble(3, amount); ps.setString(4, monthYear);
                ps.executeUpdate();
                ui(() -> { loadBudgets(); loadBudgetOverview(); notify("✅ Budget set: " + category + " → " + fmt(amount) + " for " + monthYear); });
            } catch (SQLException e) {
                LOG.severe(e.getMessage());
                ui(() -> alert(AlertType.ERROR, "Error", "Failed to save budget."));
            }
        });
    }

    private void deleteTransaction(Transaction t) {
        if (!confirm("Delete Transaction",
                     "Delete " + t.type + " transaction: " + t.category + " " + fmt(t.amount) + "?")) return;
        bg(() -> {
            try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM transactions WHERE id=? AND user_id=?")) {
                ps.setInt(1, t.id); ps.setInt(2, userId); ps.executeUpdate();
                ui(() -> { loadAll(); notify("🗑 Transaction deleted."); });
            } catch (SQLException e) { LOG.severe(e.getMessage()); }
        });
    }

    private void deleteBudget(Budget b) {
        if (!confirm("Delete Budget", "Remove budget for " + b.category + "?")) return;
        bg(() -> {
            try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM budgets WHERE id=? AND user_id=?")) {
                ps.setInt(1, b.id); ps.setInt(2, userId); ps.executeUpdate();
                ui(() -> { loadBudgets(); loadBudgetOverview(); notify("🗑 Budget removed: " + b.category); });
            } catch (SQLException e) { LOG.severe(e.getMessage()); }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  THEME
    // ══════════════════════════════════════════════════════════════════════════
    private void toggleTheme() {
        dark = !dark;
        if (themeBtn != null) themeBtn.setText(dark ? "☀️ Light" : "🌙 Dark");
        root.setStyle(dark ? "-fx-background-color:#0f172a;" : BG_LIGHT);
        // Minimal theme — a proper CSS file approach would be cleaner but we stay single-file
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  UTILITY  HELPERS
    // ══════════════════════════════════════════════════════════════════════════
    private Connection conn() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    private void bg(Runnable r)  { new Thread(r).start(); }
    private void ui(Runnable r)  { Platform.runLater(r); }

    private String fmt(double v) { return String.format("$%,.2f", v); }

    private void notify(String msg) {
        if (notifList == null) return;
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        notifList.getItems().add(0, "[" + ts + "] " + msg);
        if (notifList.getItems().size() > 60) notifList.getItems().remove(60);
    }

    private boolean confirm(String title, String msg) {
        Alert a = new Alert(AlertType.CONFIRMATION);
        a.setTitle(title); a.setHeaderText(null); a.setContentText(msg);
        return a.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private void alert(AlertType type, String title, String msg) {
        Alert a = new Alert(type); a.setTitle(title); a.setHeaderText(null); a.setContentText(msg);
        a.showAndWait();
    }

    private void logout() {
        userId = -1; username = ""; fullName = "";
        cards.clear(); navBtns.clear();
        showLogin();
    }

    private void updateCats(String type, ComboBox<String> cb) {
        cb.getItems().clear();
        if ("INCOME".equals(type))
            cb.getItems().addAll("Salary","Freelance","Investment","Gift","Business","Other Income");
        else
            cb.getItems().addAll("Food & Dining","Transportation","Shopping","Entertainment",
                                 "Healthcare","Utilities","Rent","Education","Subscriptions","Other Expense");
        cb.setValue(cb.getItems().get(0));
    }

    // ── Form helpers ──────────────────────────────────────────────────────────
    private VBox formCard(String title, String sub) {
        VBox card = new VBox(14);
        card.setMaxWidth(680);
        card.setStyle("-fx-background-color:white; -fx-background-radius:16px; "
                    + "-fx-padding:30px; -fx-border-color:#e2e8f0; -fx-border-radius:16px; "
                    + "-fx-border-width:1px; -fx-effect:dropshadow(gaussian,rgba(0,0,0,.07),14,0,0,3);");
        Label lbT = new Label(title);
        lbT.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        lbT.setStyle("-fx-text-fill:#1e293b;");
        Label lbS = new Label(sub); lbS.setStyle("-fx-text-fill:#94a3b8; -fx-font-size:13px;");
        card.getChildren().addAll(lbT, lbS, new Separator());
        return card;
    }

    private GridPane formGrid() {
        GridPane g = new GridPane(); g.setHgap(16); g.setVgap(14); g.setMaxWidth(600);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setMinWidth(120);
        ColumnConstraints c2 = new ColumnConstraints(); c2.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(c1, c2);
        return g;
    }

    private void addRow(GridPane g, String lbl, javafx.scene.Node input, int row) {
        Label l = new Label(lbl); l.setStyle("-fx-text-fill:#475569; -fx-font-weight:bold; -fx-font-size:13px;");
        g.add(l, 0, row); g.add(input, 1, row);
    }

    private Label sectionTitle(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        l.setStyle("-fx-text-fill:#1e293b;");
        return l;
    }

    private ScrollPane scrollWrap(javafx.scene.Node n) {
        ScrollPane sp = new ScrollPane(n);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color:transparent; -fx-background:transparent;");
        return sp;
    }

    private TextField styledField(String prompt) {
        TextField tf = new TextField(); tf.setPromptText(prompt);
        tf.setStyle("-fx-font-size:14px; -fx-padding:12px; -fx-background-radius:9px; "
                  + "-fx-border-color:#e2e8f0; -fx-border-radius:9px; -fx-border-width:1px;");
        return tf;
    }

    private PasswordField styledPass(String prompt) {
        PasswordField pf = new PasswordField(); pf.setPromptText(prompt);
        pf.setStyle("-fx-font-size:14px; -fx-padding:12px; -fx-background-radius:9px; "
                  + "-fx-border-color:#e2e8f0; -fx-border-radius:9px; -fx-border-width:1px;");
        return pf;
    }

    // ── Style constants ────────────────────────────────────────────────────────
    private static final String BG_LIGHT          = "-fx-background-color:#f1f5f9;";
    private static final String BG_GRADIENT_LOGIN =
        "-fx-background-color: linear-gradient(to bottom right,#6366f1,#0ea5e9);";
    private static final String CHART_STYLE        =
        "-fx-background-color:white; -fx-background-radius:14px; -fx-padding:14px; "
      + "-fx-border-color:#e2e8f0; -fx-border-radius:14px; -fx-border-width:1px; "
      + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,.06),12,0,0,2);";
    private static final String BTN_PRIMARY =
        "-fx-background-color:#6366f1; -fx-text-fill:white; "
      + "-fx-font-weight:bold; -fx-cursor:hand; -fx-background-radius:8px;";
    private static final String BTN_SUCCESS =
        "-fx-background-color:#16a34a; -fx-text-fill:white; "
      + "-fx-font-weight:bold; -fx-cursor:hand; -fx-background-radius:8px;";
    private static final String BTN_GRAY =
        "-fx-background-color:#94a3b8; -fx-text-fill:white; "
      + "-fx-cursor:hand; -fx-background-radius:8px;";
    private static final String NAV_IDLE =
        "-fx-background-color:transparent; -fx-text-fill:#94a3b8; "
      + "-fx-font-size:13px; -fx-padding:11px 14px; -fx-background-radius:8px; -fx-cursor:hand;";
    private static final String NAV_ACTIVE =
        "-fx-background-color:#6366f1; -fx-text-fill:white; "
      + "-fx-font-size:13px; -fx-padding:11px 14px; -fx-background-radius:8px; -fx-cursor:hand;";
}

// ═══════════════════════════════════════════════════════════════════════════════
//  MODEL CLASSES
// ═══════════════════════════════════════════════════════════════════════════════
class Transaction {
    final int       id, userId;
    final String    type, category, description;
    final double    amount;
    final LocalDate txDate;

    Transaction(int id, int userId, String type, String category,
                double amount, String description, LocalDate txDate) {
        this.id          = id;
        this.userId      = userId;
        this.type        = type;
        this.category    = category;
        this.amount      = amount;
        this.description = description == null ? "" : description;
        this.txDate      = txDate;
    }

    // JavaFX TableView compatibility
    public int       getId()             { return id; }
    public int       getUserId()         { return userId; }
    public String    getType()           { return type; }
    public String    getCategory()       { return category; }
    public double    getAmount()         { return amount; }
    public String    getDescription()    { return description; }
    public LocalDate getTransactionDate(){ return txDate; }
}

class Budget {
    final int    id, userId;
    final String category, monthYear;
    final double amount, spent;

    Budget(int id, int userId, String category, double amount, String monthYear, double spent) {
        this.id        = id;
        this.userId    = userId;
        this.category  = category;
        this.amount    = amount;
        this.monthYear = monthYear;
        this.spent     = spent;
    }

    double remaining() { return amount - spent; }

    String status() {
        if (amount <= 0) return "No limit";
        double pct = (spent / amount) * 100;
        if (pct >= 100) return String.format("Over budget by $%,.2f",  spent - amount);
        if (pct >= 80)  return String.format("%.0f%% used — watch closely", pct);
        return String.format("%.0f%% used — $%,.2f remaining", pct, remaining());
    }

    // JavaFX TableView compatibility
    public int    getId()        { return id; }
    public int    getUserId()    { return userId; }
    public String getCategory()  { return category; }
    public double getAmount()    { return amount; }
    public String getMonthYear() { return monthYear; }
    public double getSpent()     { return spent; }
    public double getRemaining() { return remaining(); }
    public String getStatus()    { return status(); }
}
