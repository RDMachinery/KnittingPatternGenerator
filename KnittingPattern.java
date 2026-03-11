/**
 * KnittingPattern.java
 *
 * Generates printable knitting patterns with both an ASCII chart
 * and written row-by-row instructions.
 *
 * Standard knitting chart symbols used:
 *   .  = knit stitch (on right side rows)
 *   -  = purl stitch
 *   O  = yarn over (yarnover, creates a hole in lace)
 *   /  = k2tog (knit 2 together, right-leaning decrease)
 *   \  = ssk  (slip slip knit, left-leaning decrease)
 *   X  = cable cross (simplified: 2 sts cross over 2 sts)
 *   |  = knit (used in ribbing for visual clarity)
 *   _  = purl (used in ribbing for visual clarity)
 *
 * Chart reading convention (as per standard knitting):
 *   - Charts are read bottom to top (row 1 is at the bottom)
 *   - Right side (RS) rows are read right to left
 *   - Wrong side (WS) rows are read left to right
 *
 * Four patterns are included:
 *   1. Fair Isle - geometric diamond motif in two colours
 *   2. Lace     - classic feather and fan lace
 *   3. Cable    - classic 4-stitch cable panel
 *   4. Ribbing  - 2x2 rib stitch
 *
 * @author Demonstration class
 */
public class KnittingPattern {

    // ANSI colour codes for a more attractive chart display
    static final String RESET  = "\u001B[0m";
    static final String BOLD   = "\u001B[1m";
    static final String CYAN   = "\u001B[36m";
    static final String YELLOW = "\u001B[33m";
    static final String GREEN  = "\u001B[32m";
    static final String RED    = "\u001B[31m";
    static final String BLUE   = "\u001B[34m";
    static final String WHITE  = "\u001B[37m";
    static final String BG_DARK = "\u001B[40m";

    // -----------------------------------------------------------------------
    // Pattern 1: Fair Isle Diamond Motif
    // -----------------------------------------------------------------------

    static void fairIslePattern() {

        // A 12-row, 12-stitch repeating diamond motif.
        // A = background colour (knit)
        // B = contrast colour  (knit)
        // The pattern is defined as a 2D array of colour codes.

        char[][] motif = {
            { 'A','A','A','A','A','B','B','A','A','A','A','A' },
            { 'A','A','A','A','B','A','A','B','A','A','A','A' },
            { 'A','A','A','B','A','A','A','A','B','A','A','A' },
            { 'A','A','B','A','A','A','A','A','A','B','A','A' },
            { 'A','B','A','A','A','A','A','A','A','A','B','A' },
            { 'B','A','A','A','A','A','A','A','A','A','A','B' },
            { 'B','A','A','A','A','A','A','A','A','A','A','B' },
            { 'A','B','A','A','A','A','A','A','A','A','B','A' },
            { 'A','A','B','A','A','A','A','A','A','B','A','A' },
            { 'A','A','A','B','A','A','A','A','B','A','A','A' },
            { 'A','A','A','A','B','A','A','B','A','A','A','A' },
            { 'A','A','A','A','A','B','B','A','A','A','A','A' },
        };

        int repeatCols = 2; // repeat the motif twice across
        int repeatRows = 2; // repeat the motif twice down
        int cols = motif[0].length * repeatCols;
        int rows = motif.length    * repeatRows;

        printHeader("PATTERN 1: FAIR ISLE DIAMOND MOTIF");
        System.out.println(CYAN + "Yarn A: Main colour (e.g. cream)" + RESET);
        System.out.println(CYAN + "Yarn B: Contrast colour (e.g. navy)" + RESET);
        System.out.println(CYAN + "Cast on: multiple of 12 stitches" + RESET);
        System.out.println();

        printKey(new String[]{
            YELLOW + "█" + RESET + " = Yarn A (main colour), knit stitch",
            BLUE   + "█" + RESET + " = Yarn B (contrast colour), knit stitch"
        });

        // Print chart (bottom to top for knitting convention)
        printChartBorder(cols);
        for (int row = rows - 1; row >= 0; row--) {
            int rowNum = rows - row;
            System.out.printf("%3d %s│%s", rowNum, WHITE, RESET);
            for (int col = 0; col < cols; col++) {
                char c = motif[row % motif.length][col % motif[0].length];
                if (c == 'A') {
                    System.out.print(YELLOW + "█" + RESET);
                } else {
                    System.out.print(BLUE + "█" + RESET);
                }
            }
            System.out.printf("%s│%s", WHITE, RESET);
            // RS/WS indicator
            System.out.println( rowNum % 2 == 1 ? "  ← RS (read right to left)" : "  → WS (read left to right)");
        }
        printChartBorder(cols);

        // Written instructions
        System.out.println();
        System.out.println(BOLD + GREEN + "WRITTEN INSTRUCTIONS:" + RESET);
        System.out.println("All rows: knit every stitch, changing colours as shown in chart.");
        System.out.println("Carry yarn not in use loosely across wrong side (stranded knitting).");
        System.out.println();
        System.out.println("Row 1  (RS): *K5A, K2B, K5A* repeat to end");
        System.out.println("Row 2  (WS): *P4A, P4B, P4A* repeat to end");
        System.out.println("Row 3  (RS): *K3A, K6B, K3A* repeat to end");
        System.out.println("Row 4  (WS): *P2A, P8B, P2A* repeat to end");
        System.out.println("Row 5  (RS): *K1A, K10B, K1A* repeat to end");
        System.out.println("Row 6  (RS): *K12B* repeat to end");
        System.out.println("Rows 7-12: work rows 5-1 in reverse (mirror image)");
        System.out.println("Repeat these 12 rows for pattern length required.");
    }

    // -----------------------------------------------------------------------
    // Pattern 2: Feather and Fan Lace
    // -----------------------------------------------------------------------

    static void lacePattern() {

        // Classic feather and fan - 18 stitch repeat, 4 row repeat
        // Symbols: . = knit, O = yarn over, / = k2tog, \ = ssk, - = purl

        // One repeat unit (18 stitches x 4 rows)
        char[][] motif = {
            { '.','.','.','.','.','.','.','.','.','.','.','.','.','.','.','.','.','.' }, // row 1: all knit
            { '-','-','-','-','-','-','-','-','-','-','-','-','-','-','-','-','-','-' }, // row 2: all purl
            { '/','/','/','\\','O','.','O','.','O','.','O','\\','/','/','/','.','.','.'},  // row 3: lace row
            { '-','-','-','-','-','-','-','-','-','-','-','-','-','-','-','-','-','-' }, // row 4: all purl
        };

        int repeatCols = 3;
        int cols = motif[0].length * repeatCols;
        int rows = motif.length * 4; // 4 repeats tall

        printHeader("PATTERN 2: FEATHER AND FAN LACE");
        System.out.println(CYAN + "A delicate, undulating lace pattern. Beautiful for shawls and scarves." + RESET);
        System.out.println(CYAN + "Cast on: multiple of 18 stitches" + RESET);
        System.out.println(CYAN + "Use a smooth, light-coloured yarn to show the lace to best effect." + RESET);
        System.out.println();

        printKey(new String[]{
            ". = Knit stitch",
            "- = Purl stitch",
            GREEN + "O" + RESET + " = Yarn over (creates lace hole)",
            RED   + "/" + RESET + " = K2tog (knit 2 together, right-leaning decrease)",
            YELLOW+ "\\" + RESET + " = SSK (slip slip knit, left-leaning decrease)"
        });

        printChartBorder(cols);
        for (int row = rows - 1; row >= 0; row--) {
            int rowNum = rows - row;
            System.out.printf("%3d %s│%s", rowNum, WHITE, RESET);
            for (int col = 0; col < cols; col++) {
                char c = motif[row % motif.length][col % motif[0].length];
                switch(c) {
                    case 'O': System.out.print(GREEN  + "O" + RESET); break;
                    case '/': System.out.print(RED    + "/" + RESET); break;
                    case '\\':System.out.print(YELLOW + "\\" + RESET); break;
                    case '-': System.out.print(CYAN   + "-" + RESET); break;
                    default:  System.out.print(".");
                }
            }
            System.out.printf("%s│%s", WHITE, RESET);
            System.out.println(rowNum % 2 == 1 ? "  ← RS" : "  → WS");
        }
        printChartBorder(cols);

        System.out.println();
        System.out.println(BOLD + GREEN + "WRITTEN INSTRUCTIONS:" + RESET);
        System.out.println("Row 1 (RS): Knit all stitches.");
        System.out.println("Row 2 (WS): Purl all stitches.");
        System.out.println("Row 3 (RS): *K2tog x3, (yo, k1) x6, k2tog x3* repeat to end.");
        System.out.println("Row 4 (WS): Purl all stitches.");
        System.out.println("Repeat rows 1-4 for pattern.");
        System.out.println();
        System.out.println("Note: Row 3 is stitch-count neutral — each decrease is offset by a yarn over.");
    }

    // -----------------------------------------------------------------------
    // Pattern 3: Classic Cable Panel
    // -----------------------------------------------------------------------

    static void cablePattern() {

        // 12-stitch cable panel, 8-row repeat
        // The cable crosses on row 5: first 4 sts held to front, knit next 4, knit held sts

        printHeader("PATTERN 3: CLASSIC 8-STITCH ROPE CABLE");
        System.out.println(CYAN + "A bold, classic rope cable panel. Perfect for jumpers and cushions." + RESET);
        System.out.println(CYAN + "Panel width: 12 stitches (2 purl border + 8 cable + 2 purl border)" + RESET);
        System.out.println();

        printKey(new String[]{
            ". = Knit stitch",
            "- = Purl stitch",
            YELLOW + "X" + RESET + " = C8F: slip 4 sts to cable needle, hold to FRONT,",
            "      knit 4, then knit 4 from cable needle"
        });

        // 12 stitches wide, 8 row repeat, shown 3 times
        char[][] rows = {
            { '-','-','.','.','.','.','.','.','.','.','-','-' }, // row 1 RS
            { '.','.', '-','-','-','-','-','-','-','-','.','.'},  // row 2 WS
            { '-','-','.','.','.','.','.','.','.','.','-','-' }, // row 3 RS
            { '.','.', '-','-','-','-','-','-','-','-','.','.'},  // row 4 WS
            { '-','-','X','X','X','X','X','X','X','X','-','-' }, // row 5 RS - cable row
            { '.','.', '-','-','-','-','-','-','-','-','.','.'},  // row 6 WS
            { '-','-','.','.','.','.','.','.','.','.','-','-' }, // row 7 RS
            { '.','.', '-','-','-','-','-','-','-','-','.','.'},  // row 8 WS
        };

        int totalRows = rows.length * 3;
        int cols = rows[0].length;

        printChartBorder(cols);
        for (int row = totalRows - 1; row >= 0; row--) {
            int rowNum = totalRows - row;
            System.out.printf("%3d %s│%s", rowNum, WHITE, RESET);
            for (int col = 0; col < cols; col++) {
                char c = rows[row % rows.length][col];
                switch(c) {
                    case 'X': System.out.print(YELLOW + "X" + RESET); break;
                    case '-': System.out.print(CYAN   + "-" + RESET); break;
                    default:  System.out.print(".");
                }
            }
            System.out.printf("%s│%s", WHITE, RESET);
            System.out.println(rowNum % 2 == 1 ? "  ← RS" : "  → WS");
        }
        printChartBorder(cols);

        System.out.println();
        System.out.println(BOLD + GREEN + "WRITTEN INSTRUCTIONS:" + RESET);
        System.out.println("Row 1 (RS): P2, K8, P2.");
        System.out.println("Row 2 (WS): K2, P8, K2.");
        System.out.println("Row 3 (RS): P2, K8, P2.");
        System.out.println("Row 4 (WS): K2, P8, K2.");
        System.out.println("Row 5 (RS): P2, C8F, P2.   <-- CABLE CROSS ROW");
        System.out.println("Row 6 (WS): K2, P8, K2.");
        System.out.println("Row 7 (RS): P2, K8, P2.");
        System.out.println("Row 8 (WS): K2, P8, K2.");
        System.out.println("Repeat rows 1-8 for length required.");
        System.out.println();
        System.out.println("C8F: Slip 4 sts to cable needle, hold to FRONT of work,");
        System.out.println("     K4 from left needle, K4 from cable needle.");
    }

    // -----------------------------------------------------------------------
    // Pattern 4: 2x2 Ribbing
    // -----------------------------------------------------------------------

    static void ribbingPattern() {

        printHeader("PATTERN 4: 2x2 RIB STITCH");
        System.out.println(CYAN + "Classic, stretchy ribbing. Ideal for cuffs, collars and hat brims." + RESET);
        System.out.println(CYAN + "Cast on: multiple of 4 stitches." + RESET);
        System.out.println();

        printKey(new String[]{
            BOLD + "|" + RESET + " = Knit stitch (RS) / Purl stitch (WS)",
            CYAN + "_" + RESET + " = Purl stitch (RS) / Knit stitch (WS)"
        });

        int cols = 20; // 5 repeats of 4-stitch rib
        int totalRows = 16;

        printChartBorder(cols);
        for (int row = totalRows - 1; row >= 0; row--) {
            int rowNum = totalRows - row;
            boolean isRS = (rowNum % 2 == 1);
            System.out.printf("%3d %s│%s", rowNum, WHITE, RESET);
            for (int col = 0; col < cols; col++) {
                // 2x2 rib: columns 0,1 = knit columns, 2,3 = purl columns (in 4-stitch repeat)
                boolean isKnitCol = (col % 4 < 2);
                if (isRS) {
                    System.out.print(isKnitCol ? (BOLD + "|" + RESET) : (CYAN + "_" + RESET));
                } else {
                    // On WS, knit and purl are swapped to maintain the rib
                    System.out.print(isKnitCol ? (CYAN + "_" + RESET) : (BOLD + "|" + RESET));
                }
            }
            System.out.printf("%s│%s", WHITE, RESET);
            System.out.println(isRS ? "  ← RS" : "  → WS");
        }
        printChartBorder(cols);

        System.out.println();
        System.out.println(BOLD + GREEN + "WRITTEN INSTRUCTIONS:" + RESET);
        System.out.println("Row 1 (RS): *K2, P2* repeat to end.");
        System.out.println("Row 2 (WS): *K2, P2* repeat to end.");
        System.out.println("(The rib is self-correcting — the knits and purls align naturally.)");
        System.out.println("Repeat these 2 rows for depth required.");
        System.out.println();
        System.out.println("Tip: For a neater edge, slip the first stitch of every row purlwise.");
    }

    // -----------------------------------------------------------------------
    // Utility methods
    // -----------------------------------------------------------------------

    static void printHeader(String title) {
        System.out.println();
        System.out.println(BOLD + "╔══════════════════════════════════════════════════╗" + RESET);
        System.out.printf(  BOLD + "║  %-48s║%n" + RESET, title);
        System.out.println(BOLD + "╚══════════════════════════════════════════════════╝" + RESET);
        System.out.println();
    }

    static void printKey(String[] entries) {
        System.out.println(BOLD + "KEY:" + RESET);
        for (String entry : entries) {
            System.out.println("  " + entry);
        }
        System.out.println();
    }

    static void printChartBorder(int cols) {
        System.out.print("    " + WHITE + "+" + RESET);
        for (int i = 0; i < cols; i++) System.out.print(WHITE + "-" + RESET);
        System.out.println(WHITE + "+" + RESET);
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) {

        System.out.println(BOLD + CYAN);
        System.out.println("  ██╗  ██╗███╗   ██╗██╗████████╗████████╗██╗███╗   ██╗ ██████╗ ");
        System.out.println("  ██║ ██╔╝████╗  ██║██║╚══██╔══╝╚══██╔══╝██║████╗  ██║██╔════╝ ");
        System.out.println("  █████╔╝ ██╔██╗ ██║██║   ██║      ██║   ██║██╔██╗ ██║██║  ███╗");
        System.out.println("  ██╔═██╗ ██║╚██╗██║██║   ██║      ██║   ██║██║╚██╗██║██║   ██║");
        System.out.println("  ██║  ██╗██║ ╚████║██║   ██║      ██║   ██║██║ ╚████║╚██████╔╝");
        System.out.println("  ╚═╝  ╚═╝╚═╝  ╚═══╝╚═╝   ╚═╝      ╚═╝   ╚═╝╚═╝  ╚═══╝ ╚═════╝ ");
        System.out.println("              P A T T E R N   G E N E R A T O R" + RESET);
        System.out.println();
        System.out.println(CYAN + "Charts are read from BOTTOM to TOP, as in standard knitting convention." + RESET);
        System.out.println(CYAN + "RS rows (odd) are read right to left. WS rows (even) left to right." + RESET);

        fairIslePattern();
        lacePattern();
        cablePattern();
        ribbingPattern();

        System.out.println();
        System.out.println(BOLD + GREEN + "Happy knitting!" + RESET);
    }
}
