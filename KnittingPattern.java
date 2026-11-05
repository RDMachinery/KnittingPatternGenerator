/*
 * KnittingPattern.java - Knitting Pattern Studio
 *
 * A single-file Java application for viewing, designing and following
 * knitting charts. Every chart is the single source of truth: the written
 * row-by-row instructions, the symbol key, the stitch-count checks, the
 * printed pattern and the row tracker are all generated from it, so the
 * chart and the words can never disagree.
 *
 * Run it (Java 17 or newer):
 *
 *     java KnittingPattern.java                     opens the window
 *     java KnittingPattern.java my-scarf.knit       opens a saved pattern
 *     java KnittingPattern.java --text              prints every pattern as text
 *     java KnittingPattern.java --text --pattern lace --no-colour
 *
 * Chart conventions (standard knitting practice):
 *   - Charts are read from the bottom up; row 1 is at the bottom.
 *   - Each square shows how the stitch looks from the right side (RS).
 *   - RS rows are read right to left, wrong-side (WS) rows left to right.
 *   - In the round, every round is an RS round, read right to left.
 *   - Each square is one stitch left on the needle after the row is worked,
 *     so a decrease (which uses 2 or 3 stitches) still takes one square.
 *
 * Licence: MIT.
 */

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import java.util.prefs.Preferences;

public class KnittingPattern {

    static final String APP_NAME = "Knitting Pattern Studio";
    static final String VERSION = "2.1";

    // =======================================================================
    // Stitches
    // =======================================================================

    /**
     * Every stitch the charts understand. {@code consumes} is how many
     * stitches it takes off the left needle; {@code produces} is how many it
     * leaves on the right needle. Cables span {@code width} squares.
     */
    enum Stitch {
        KNIT     ('.', 1, 1, 1, "k",     "p",     "Knit"),
        PURL     ('-', 1, 1, 1, "p",     "k",     "Purl"),
        YO       ('O', 0, 1, 1, "yo",    "yo",    "Yarn over"),
        K2TOG    ('/', 2, 1, 1, "k2tog", "p2tog", "K2tog"),
        SSK      ('\\',2, 1, 1, "ssk",   "ssp",   "SSK"),
        SK2P     ('^', 3, 1, 1, "sk2p",  null,    "SK2P"),
        M1L      ('L', 0, 1, 1, "M1L",   null,    "M1L"),
        M1R      ('R', 0, 1, 1, "M1R",   null,    "M1R"),
        SL1      ('V', 1, 1, 1, "sl1 wyib", "sl1 wyif", "Slip 1"),
        K1TBL    ('T', 1, 1, 1, "k1tbl", "p1tbl", "K1tbl"),
        BOBBLE   ('@', 1, 1, 1, "MB",    null,    "Bobble"),
        C2F      ('C', 1, 1, 2, "C2F",   null,    "C2F"),
        C2B      ('D', 1, 1, 2, "C2B",   null,    "C2B"),
        C4F      ('F', 1, 1, 4, "C4F",   null,    "C4F"),
        C4B      ('B', 1, 1, 4, "C4B",   null,    "C4B"),
        C6F      ('G', 1, 1, 6, "C6F",   null,    "C6F"),
        C6B      ('H', 1, 1, 6, "C6B",   null,    "C6B"),
        C8F      ('X', 1, 1, 8, "C8F",   null,    "C8F"),
        C8B      ('Y', 1, 1, 8, "C8B",   null,    "C8B"),
        T4F      ('P', 1, 1, 4, "T4F",   null,    "T4F"),
        T4B      ('Q', 1, 1, 4, "T4B",   null,    "T4B"),
        NO_STITCH('#', 0, 0, 1, "",      "",      "No stitch");

        final char symbol;
        final int consumes, produces, width;
        final String rs, ws, label;

        Stitch(char symbol, int consumes, int produces, int width, String rs, String ws, String label) {
            this.symbol = symbol; this.consumes = consumes; this.produces = produces;
            this.width = width; this.rs = rs; this.ws = ws; this.label = label;
        }

        boolean isCable()       { return width > 1; }
        boolean frontCross()    { return isCable() && name().endsWith("F"); }
        /** A travelling stitch: knit stitches crossing over purl ones, rather than knit over knit. */
        boolean travels()       { return isCable() && name().startsWith("T"); }
        boolean rightSideOnly() { return ws == null; }

        String description(boolean inTheRound) {
            switch (this) {
                case KNIT:  return inTheRound ? "Knit." : "Knit on right-side rows; purl on wrong-side rows.";
                case PURL:  return inTheRound ? "Purl." : "Purl on right-side rows; knit on wrong-side rows.";
                case YO:    return "Yarn over: take the yarn over the needle to make a new stitch and a small decorative hole.";
                case K2TOG: return "K2tog: knit 2 stitches together. A right-leaning decrease" + (inTheRound ? "." : " (on a wrong-side row, p2tog).");
                case SSK:   return "SSK: slip 2 stitches knitwise one at a time, then knit them together through the back loops. "
                                 + "A left-leaning decrease" + (inTheRound ? "." : " (on a wrong-side row, ssp).");
                case SK2P:  return "SK2P: slip 1 knitwise, k2tog, then pass the slipped stitch over. A double decrease: 3 stitches become 1.";
                case M1L:   return "M1L: pick up the bar between stitches from front to back and knit it through the back loop. A left-leaning increase.";
                case M1R:   return "M1R: pick up the bar between stitches from back to front and knit it through the front loop. A right-leaning increase.";
                case SL1:   return "Slip 1: slip the next stitch purlwise to the right needle without working it, "
                                 + "with the yarn at the back" + (inTheRound ? "." : " (on a wrong-side row, with the yarn at the front, "
                                 + "so the strand stays on the wrong side).");
                case K1TBL: return "K1tbl: knit 1 through the back loop. This twists the stitch so it stands up crisp and neat"
                                 + (inTheRound ? "." : " (on a wrong-side row, p1tbl).");
                case BOBBLE: return "Make bobble: (k1, p1, k1, p1, k1) all into the next stitch to make 5 stitches; turn and p5; "
                                 + "turn and k5; then pass the 2nd, 3rd, 4th and 5th stitches over the first. 1 stitch becomes a round knob.";
                case NO_STITCH: return "No stitch: a placeholder square. Skip it and carry on with the next square.";
                case T4F:   return "T4F (twist 4 front): slip 2 stitches to a cable needle and hold at the front of the work, purl 2, "
                                 + "then knit 2 from the cable needle. The knit stitches travel to the left over the purl background.";
                case T4B:   return "T4B (twist 4 back): slip 2 stitches to a cable needle and hold at the back of the work, knit 2, "
                                 + "then purl 2 from the cable needle. The knit stitches travel to the right over the purl background.";
                default: {
                    int h = width / 2;
                    String sts = h == 1 ? "1 stitch" : h + " stitches";
                    return label + ": slip " + sts + " to a cable needle and hold at the "
                         + (frontCross() ? "front" : "back") + " of the work, knit " + h
                         + ", then knit " + h + " from the cable needle."
                         + (h == 1 ? " (Many knitters do this without a cable needle.)" : "");
                }
            }
        }

        static Stitch fromSymbol(char ch) {
            for (Stitch s : values()) if (s.symbol == ch) return s;
            return null;
        }
    }

    /** A yarn used in a chart: letter shown in instructions, a friendly name and a display colour. */
    record Yarn(String letter, String name, Color color) { }

    static final Yarn[] DEFAULT_YARNS = {
        new Yarn("A", "Cream",   new Color(0xF3EAD7)),
        new Yarn("B", "Navy",    new Color(0x1F3A68)),
        new Yarn("C", "Rust",    new Color(0xB5532D)),
        new Yarn("D", "Sage",    new Color(0x8AA37B)),
        new Yarn("E", "Mustard", new Color(0xD9A43B)),
        new Yarn("F", "Plum",    new Color(0x6B3A5B)),
    };

    // =======================================================================
    // Chart - the single source of truth for a pattern
    // =======================================================================

    static final class Chart {
        String name = "Untitled pattern", description = "", notes = "";
        boolean inTheRound, panel, builtIn, dirty;
        Stitch[][] cells;          // [row][col]; row 0 is chart row 1 (bottom); col 0 is the leftmost square
        int[][] colours;           // yarn index for each square
        List<Yarn> yarns = new ArrayList<>();

        Chart(int width, int height) {
            cells = new Stitch[height][width];
            colours = new int[height][width];
            for (Stitch[] row : cells) Arrays.fill(row, Stitch.KNIT);
            yarns.add(DEFAULT_YARNS[0]);
        }

        int width()  { return cells[0].length; }
        int height() { return cells.length; }
        boolean isColourwork() { return yarns.size() > 1; }
        /** Side of a chart row (0-based). Flat odd rows (1, 3, 5...) are right-side rows. */
        boolean isRS(int row)  { return inTheRound || row % 2 == 0; }
        String rowWord()  { return inTheRound ? "Round" : "Row"; }
        String rowsWord() { return inTheRound ? "rounds" : "rows"; }

        int yarnIndex(int r, int c) { return Math.max(0, Math.min(yarns.size() - 1, colours[r][c])); }
        Yarn yarn(int r, int c)     { return yarns.get(yarnIndex(r, c)); }
        Color fill(int r, int c)    { return isColourwork() ? yarn(r, c).color() : Renderer.PAPER; }

        boolean uses(Stitch s) {
            for (Stitch[] row : cells) for (Stitch x : row) if (x == s) return true;
            return false;
        }
        boolean hasCables() {
            for (Stitch[] row : cells) for (Stitch x : row) if (x.isCable()) return true;
            return false;
        }

        String summary() {
            String kind = isColourwork() ? "colourwork" : hasCables() ? "cables" : uses(Stitch.YO) ? "lace" : "texture";
            return width() + " × " + height() + " " + rowsWord() + " · " + kind + (panel ? " panel" : "");
        }

        Chart copy() {
            Chart k = new Chart(width(), height());
            k.name = name; k.description = description; k.notes = notes;
            k.inTheRound = inTheRound; k.panel = panel; k.builtIn = builtIn;
            k.restoreFrom(this);
            return k;
        }

        void restoreFrom(Chart o) {
            cells = new Stitch[o.height()][];
            colours = new int[o.height()][];
            for (int r = 0; r < o.height(); r++) { cells[r] = o.cells[r].clone(); colours[r] = o.colours[r].clone(); }
            yarns = new ArrayList<>(o.yarns);
        }

        /** Resizes the chart, keeping the bottom-left corner in place. */
        void resize(int w, int h) {
            Stitch[][] nc = new Stitch[h][w];
            int[][] ncol = new int[h][w];
            for (int r = 0; r < h; r++)
                for (int c = 0; c < w; c++) {
                    boolean keep = r < height() && c < width();
                    nc[r][c] = keep ? cells[r][c] : Stitch.KNIT;
                    ncol[r][c] = keep ? colours[r][c] : 0;
                }
            cells = nc; colours = ncol;
        }

        @Override public String toString() { return name; }
    }

    // =======================================================================
    // Built-in patterns
    // =======================================================================

    static final class BuiltIns {

        static List<Chart> all() {
            return new ArrayList<>(List.of(fairIsle(), houndstooth(), featherAndFan(), horseshoeLace(), ropeCable(), horseshoeCable(),
                    honeycombCable(), bobbleDiamond(), miniCableRib(), garterStitch(), rib2x2(), twistedRib(), seedStitch(),
                    basketweave(), heelStitch()));
        }

        /** Builds a chart from rows written top-down, exactly as the chart looks on paper. */
        static Chart build(String name, boolean round, boolean panel, String[] stitchRows, String[] colourRows, Yarn... yarns) {
            int h = stitchRows.length, w = stitchRows[0].length();
            Chart c = new Chart(w, h);
            c.name = name; c.inTheRound = round; c.panel = panel; c.builtIn = true;
            if (yarns.length > 0) { c.yarns.clear(); c.yarns.addAll(List.of(yarns)); }
            for (int i = 0; i < h; i++) {
                int r = h - 1 - i;
                for (int col = 0; col < w; col++) {
                    c.cells[r][col] = Objects.requireNonNull(Stitch.fromSymbol(stitchRows[i].charAt(col)));
                    if (colourRows != null) c.colours[r][col] = colourRows[i].charAt(col) - 'A';
                }
            }
            return c;
        }

        static String[] repeatRow(String row, int times) {
            String[] out = new String[times];
            Arrays.fill(out, row);
            return out;
        }

        static Chart fairIsle() {
            // Rows 1-6 then their mirror image, written top-down. The motif is
            // symmetric, so top-down and bottom-up read the same. The longest
            // run of either colour is 4 stitches, so floats stay short.
            String r1 = "BBAAABBAAABB", r2 = "BBAABAABAABB", r3 = "BAABAAAABAAB",
                   r4 = "AABAABBAABAA", r5 = "ABAABBBBAABA", r6 = "BAAABBBBAAAB";
            String[] colours = { r1, r2, r3, r4, r5, r6, r6, r5, r4, r3, r2, r1 };
            Chart c = build("Fair Isle Diamonds", true, false, repeatRow("............", 12), colours,
                    new Yarn("A", "Cream", new Color(0xF3EAD7)), new Yarn("B", "Navy", new Color(0x1F3A68)));
            c.description = "Large outlined diamonds with a solid centre, separated by small diamonds where the repeats meet. "
                          + "Knitted in the round in two colours.";
            c.notes = "Every round is knitted, so every round is read from right to left.\n"
                    + "Hold both yarns and strand the one you are not using loosely across the back. "
                    + "The longest float in this motif is 4 stitches, so you don't need to catch any floats.\n"
                    + "Stranded knitting is firmer than plain knitting: knit a gauge swatch in the round, "
                    + "and go up a needle size if your fabric puckers.";
            return c;
        }

        static Chart houndstooth() {
            String[] colours = { "ABBB", "AAAB", "AAAB", "BAAA" };
            Chart c = build("Houndstooth", true, false, repeatRow("....", 4), colours,
                    new Yarn("A", "Cream", new Color(0xF3EAD7)), new Yarn("B", "Charcoal", new Color(0x3A3634)));
            c.description = "The classic broken check, knitted small. A tiny 4-stitch repeat that's quick to memorise. "
                          + "Knitted in the round in two colours; lovely for hats, cowls and mittens.";
            c.notes = "Every round is knitted, so every round is read from right to left.\n"
                    + "The longest float is only 3 stitches, so this is a good first stranded pattern.\n"
                    + "Strong contrast shows the teeth best: try charcoal and cream, or black and white.";
            return c;
        }

        static Chart horseshoeLace() {
            Chart c = build("Horseshoe Lace", false, false, new String[] {
                    "..........",   // row 8 (WS): purl
                    "....O^O...",   // row 7 (RS)
                    "-.........",   // row 6 (WS): k1, p9
                    "-..O.^.O..",   // row 5 (RS)
                    "-.........",   // row 4 (WS): k1, p9
                    "-.O..^..O.",   // row 3 (RS)
                    "..........",   // row 2 (WS): purl
                    ".O...^...O",   // row 1 (RS)
            }, null);
            c.description = "A traditional Shetland lace of rounded arches, like rows of horseshoes. "
                          + "Each arch narrows to a central double decrease. A favourite for shawls and stoles.";
            c.notes = "Every right-side row is balanced: two yarn overs replace the two stitches lost to the SK2P, "
                    + "so the stitch count never changes.\n"
                    + "For a symmetrical edge, cast on a multiple of 10 plus 1, and work the extra stitch at the end of each row "
                    + "like the left-hand square of the chart (the column with the purl bumps).\n"
                    + "Use a smooth yarn and block firmly to open the arches.";
            return c;
        }

        static Chart featherAndFan() {
            Chart c = build("Feather and Fan Lace", false, false, new String[] {
                    "------------------",   // row 4 (WS): knit, shown as purl bumps from the right side
                    "///.O.O.O.O.O.O///",   // row 3 (RS): the lace row
                    "..................",   // row 2 (WS): purl, shown as knit from the right side
                    "..................",   // row 1 (RS): knit
            }, null);
            c.description = "The classic Shetland 'Old Shale' lace: gentle waves with a scalloped edge. "
                          + "Lovely for shawls, scarves and blanket borders.";
            c.notes = "Row 3 is balanced: six yarn overs replace the six stitches lost to the decreases, "
                    + "so you always end with the same number of stitches.\n"
                    + "Knitting row 4 on the wrong side makes the little garter ridge that defines the pattern. "
                    + "For a smoother variant, purl row 4 instead.\n"
                    + "Use a smooth, light-coloured yarn and block the finished piece to open up the lace.";
            return c;
        }

        static Chart ropeCable() {
            String plain = "--........--";
            Chart c = build("Rope Cable (C8F)", false, true, new String[] {
                    plain, plain, plain, "--XXXXXXXX--", plain, plain, plain, plain }, null);
            c.description = "A bold rope cable: 8 knit stitches crossed every 8 rows, framed by 2 purl stitches each side. "
                          + "Perfect for jumpers and cushions.";
            c.notes = "This is a panel: work its 12 stitches wherever you want the cable, with a stitch marker each side.\n"
                    + "Crossing every 8 rows gives an open, rounded rope. Cross every 6 rows for a tighter twist.\n"
                    + "Cables pull the fabric in, so a cabled piece needs more stitches than a plain one of the same width.";
            return c;
        }

        static Chart horseshoeCable() {
            String plain = "--........--";
            Chart c = build("Horseshoe Cable", false, true, new String[] {
                    plain, plain, plain, "--FFFFBBBB--", plain, plain, plain, plain }, null);
            c.description = "Two cables crossing away from each other, forming a horseshoe shape. "
                          + "Uses a back cross and a front cross on the same row.";
            c.notes = "Work C4B first, then C4F, as the chart is read from right to left.\n"
                    + "Swap them (C4F, then C4B) and the horseshoe turns upside down.";
            return c;
        }

        static Chart honeycombCable() {
            Chart c = build("Honeycomb Cable", false, false, new String[] {
                    "........",   // row 8 (WS): purl
                    "........",   // row 7 (RS): knit
                    "........",   // row 6 (WS): purl
                    "BBBBFFFF",   // row 5 (RS): C4F, C4B
                    "........",   // row 4 (WS): purl
                    "........",   // row 3 (RS): knit
                    "........",   // row 2 (WS): purl
                    "FFFFBBBB",   // row 1 (RS): C4B, C4F
            }, null);
            c.description = "An all-over cable texture of interlocking cells, like a honeycomb. "
                          + "Rich and warm for cushions, blankets and aran jumpers.";
            c.notes = "Work the cables in the order shown, reading right to left: C4B then C4F on row 1, C4F then C4B on row 5.\n"
                    + "Honeycomb pulls in a lot. Cast on noticeably more stitches than for plain knitting, "
                    + "and always knit a swatch.";
            return c;
        }

        static Chart bobbleDiamond() {
            Chart c = build("Bobble Diamond", false, true, new String[] {
                    "----..-..----",   // row 8 (WS)
                    "--QQQQ-PPPP--",   // row 7 (RS): the strands meet
                    "--..-----..--",   // row 6 (WS)
                    "QQQQ-----PPPP",   // row 5 (RS): the strands turn back
                    "..---------..",   // row 4 (WS)
                    "PPPP--@--QQQQ",   // row 3 (RS): the bobble, at the widest point
                    "--..-----..--",   // row 2 (WS)
                    "--PPPP-QQQQ--",   // row 1 (RS): the strands part
            }, null);
            c.description = "Two strands of knit stitches travel apart and back again over a purl background, "
                          + "making a chain of diamonds with a bobble in the middle of each. A classic aran panel.";
            c.notes = "This is a panel: work its 13 stitches wherever you want it, with a stitch marker each side.\n"
                    + "On wrong-side rows, knit the knits and purl the purls as they face you.\n"
                    + "T4F moves the knit stitches to the left and T4B moves them to the right. "
                    + "Work bobbles firmly so they pop out on the right side.";
            return c;
        }

        static Chart miniCableRib() {
            Chart c = build("Mini Cable Rib", false, false, new String[] { "-..-", "-..-", "-..-", "-CC-" }, null);
            c.description = "A rib with a tiny 2-stitch cable twisting up each knit column. "
                          + "All the stretch of ribbing, with more texture. Lovely for socks, hats and cuffs.";
            c.notes = "C2F crosses just two stitches, so you can work it without a cable needle: reach behind the first stitch "
                    + "and knit the second one through the back loop, then knit the first stitch, and slip both off together.\n"
                    + "For a cuff, cast on a multiple of 4 and work in rib from the start.";
            return c;
        }

        static Chart twistedRib() {
            Chart c = build("Twisted Rib", false, false, new String[] { "-T", "-T" }, null);
            c.description = "A 1×1 rib with every knit stitch twisted. Crisp, neat columns that hold their shape. "
                          + "A favourite for sock cuffs and traditional ganseys.";
            c.notes = "Twisting the stitches makes the rib firmer and less stretchy than plain 1×1 rib.\n"
                    + "On wrong-side rows the twisted stitches are purled through the back loop (p1tbl).";
            return c;
        }

        static Chart heelStitch() {
            Chart c = build("Heel Stitch", false, false, new String[] { "..", ".V" }, null);
            c.description = "Slipped stitches make a thick, ribbed fabric that wears well. "
                          + "Traditionally used for sock heels, but just as good for a hard-wearing bag or a cosy mug cosy.";
            c.notes = "Slip the stitches purlwise with the yarn at the back, so the strand lies on the wrong side.\n"
                    + "Slipped stitches pull the fabric up, so heel stitch is shorter and denser than stocking stitch.\n"
                    + "For a sock heel, work it back and forth over half the stitches.";
            return c;
        }

        static Chart garterStitch() {
            Chart c = build("Garter Stitch", false, false, new String[] { "--", ".." }, null);
            c.description = "The first stitch everyone learns: knit every row. A squishy, ridged fabric that lies flat "
                          + "and looks the same on both sides.";
            c.notes = "Knit every row. The chart shows the wrong-side rows as purl bumps because that's how they look from the right side.\n"
                    + "Each ridge is two rows, so count ridges instead of rows to keep track.\n"
                    + "Garter stitch is wider and shorter than stocking stitch at the same gauge, so swatch it in garter.";
            return c;
        }

        static Chart rib2x2() {
            Chart c = build("2×2 Rib", false, false, new String[] { "--..", "--.." }, null);
            c.description = "Classic, stretchy ribbing. Ideal for cuffs, collars and hat brims.";
            c.notes = "Knit the knits and purl the purls as they face you.\n"
                    + "Use a needle about half a size to one size smaller than for the main fabric.\n"
                    + "For a scarf with matching edges, cast on a multiple of 4 plus 2 and finish every row with k2.";
            return c;
        }

        static Chart basketweave() {
            String a = "----....", b = "....----";
            Chart c = build("Basketweave", false, false, new String[] { b, b, b, b, a, a, a, a }, null);
            c.description = "Alternating blocks of knit and purl that look like a woven basket. "
                          + "It lies flat, looks good on both sides, and is very satisfying to knit. Great for dishcloths, blankets and bags.";
            c.notes = "Knit the knits and purl the purls as they face you. After four rows, swap the blocks over.\n"
                    + "For a scarf with matching edges, cast on a multiple of 8 plus 4, and finish each row by working the first 4 stitches of the chart again.";
            return c;
        }

        static Chart seedStitch() {
            Chart c = build("Seed (Moss) Stitch", false, false, new String[] { ".-", "-." }, null);
            c.description = "A bumpy, reversible texture that lies flat and doesn't curl. "
                          + "Called moss stitch in the UK and seed stitch in the US.";
            c.notes = "Knit the purls and purl the knits as they face you, so the bumps sit in a checkerboard.\n"
                    + "Great for borders on stocking-stitch pieces because it doesn't roll.";
            return c;
        }
    }

    // =======================================================================
    // Written instructions - generated from the chart
    // =======================================================================

    static final class Instructions {

        /** One instruction: "k5 A", "k2tog 3 times", "C8F". */
        record Tok(String ab, String yarn, int count) {
            boolean countable() { return ab.equals("k") || ab.equals("p"); }
            String text() {
                String y = yarn.isEmpty() ? "" : " " + yarn;
                return countable() ? ab + count + y : ab + y + times(count);
            }
        }

        static String times(int n) { return n == 1 ? "" : n == 2 ? " twice" : " " + n + " times"; }

        static String label(Chart c, int r) {
            return c.inTheRound ? "Round " + (r + 1) : "Row " + (r + 1) + (c.isRS(r) ? " (RS)" : " (WS)");
        }

        /** Tokens for a chart row, in the order the knitter works them. */
        static List<Tok> tokens(Chart c, int r) {
            boolean rs = c.isRS(r);
            int w = c.width();
            List<Tok> toks = new ArrayList<>();
            for (int i = 0; i < w; i++) {
                int col = rs ? w - 1 - i : i;
                Stitch s = c.cells[r][col];
                if (s == Stitch.NO_STITCH) continue;
                String yarn = c.isColourwork() ? c.yarn(r, col).letter() : "";
                String ab = rs || s.ws == null ? s.rs : s.ws;
                if (s.isCable()) {
                    int run = 1;
                    while (i + run < w && c.cells[r][rs ? w - 1 - i - run : i + run] == s) run++;
                    int units = Math.max(1, run / s.width);
                    toks.add(new Tok(ab, yarn, units));
                    i += run - 1;
                    continue;
                }
                Tok last = toks.isEmpty() ? null : toks.get(toks.size() - 1);
                if (last != null && last.ab().equals(ab) && last.yarn().equals(yarn) && !s.isCable())
                    toks.set(toks.size() - 1, new Tok(ab, yarn, last.count() + 1));
                else
                    toks.add(new Tok(ab, yarn, 1));
            }
            return toks;
        }

        /** One stitch to work: its leftmost square, how many squares it covers, and what to do. */
        record Step(int col, int span, String text) { }

        /**
         * The stitches of a chart row, one per step, in the order the knitter works them.
         * A cable is a single step covering all its squares; "no stitch" squares are skipped.
         */
        static List<Step> steps(Chart c, int r) {
            boolean rs = c.isRS(r);
            int w = c.width();
            List<Step> out = new ArrayList<>();
            for (int i = 0; i < w; i++) {
                int col = rs ? w - 1 - i : i;
                Stitch s = c.cells[r][col];
                if (s == Stitch.NO_STITCH) continue;
                String ab = rs || s.ws == null ? s.rs : s.ws;
                String yarn = c.isColourwork() ? " " + c.yarn(r, col).letter() : "";
                if (!s.isCable()) { out.add(new Step(col, 1, ab + yarn)); continue; }
                int a = col;
                while (a > 0 && c.cells[r][a - 1] == s) a--;
                int b = col;
                while (b + 1 < w && c.cells[r][b + 1] == s) b++;
                int start = a + ((col - a) / s.width) * s.width, span = Math.min(s.width, b + 1 - start);
                out.add(new Step(start, span, ab + yarn));
                i += span - 1;
            }
            return out;
        }

        /** Groups repeated sequences: [yo, k1, yo, k1, ...] becomes "(yo, k1) 6 times". */
        static List<String> compress(List<Tok> toks) {
            List<String> out = new ArrayList<>();
            int i = 0;
            while (i < toks.size()) {
                int bestP = 0, bestK = 1;
                for (int p = 2; p <= 4; p++) {
                    int k = 1;
                    while (i + (k + 1) * p <= toks.size() && sameRun(toks, i, i + k * p, p)) k++;
                    if (k >= 2 && p * k > bestP * bestK) { bestP = p; bestK = k; }
                }
                if (bestP > 0) {
                    List<String> group = new ArrayList<>();
                    for (int j = 0; j < bestP; j++) group.add(toks.get(i + j).text());
                    out.add("(" + String.join(", ", group) + ")" + times(bestK));
                    i += bestP * bestK;
                } else {
                    out.add(toks.get(i).text());
                    i++;
                }
            }
            return out;
        }

        private static boolean sameRun(List<Tok> t, int a, int b, int len) {
            for (int j = 0; j < len; j++) if (!t.get(a + j).equals(t.get(b + j))) return false;
            return true;
        }

        /** The full written instruction for one chart row. */
        static String body(Chart c, int r) {
            List<Tok> toks = tokens(c, r);
            if (toks.isEmpty()) return "No stitches.";
            if (toks.size() == 1 && toks.get(0).countable()) {
                Tok t = toks.get(0);
                String verb = t.ab().equals("k") ? "Knit" : "Purl";
                return t.yarn().isEmpty() ? verb + "." : verb + " every stitch with yarn " + t.yarn() + ".";
            }
            String seq = String.join(", ", compress(toks));
            return c.panel ? seq + "." : "*" + seq + "; repeat from * to end.";
        }

        /** Every row as {label, text}; a row identical to an earlier one says "As row 1." */
        static List<String[]> rows(Chart c) {
            Map<String, Integer> seen = new HashMap<>();
            List<String[]> out = new ArrayList<>();
            for (int r = 0; r < c.height(); r++) {
                String b = body(c, r);
                String key = (c.isRS(r) ? "RS:" : "WS:") + b;
                Integer first = seen.get(key);
                if (first == null) seen.put(key, r + 1);
                String text = first == null ? b : "As " + c.rowWord().toLowerCase() + " " + first + ".";
                out.add(new String[] { label(c, r), text });
            }
            return out;
        }

        static String castOn(Chart c) {
            int w = c.width(), h = c.height();
            if (c.panel)
                return "This is a panel " + w + " stitches wide: work it between your other stitches, with a marker each side. "
                     + "It repeats every " + h + " " + c.rowsWord() + ".";
            return "Cast on a multiple of " + w + " stitches" + (c.inTheRound ? " and join to work in the round. " : ". ")
                 + "The pattern repeats every " + h + " " + c.rowsWord() + ".";
        }

        static String readingNote(Chart c) {
            return c.inTheRound
                 ? "Read the chart from the bottom up; every round is read from right to left."
                 : "Read the chart from the bottom up. Right-side (odd) rows are read from right to left, "
                 + "wrong-side (even) rows from left to right.";
        }

        static String repeatLine(Chart c) {
            return "Repeat " + c.rowsWord() + " 1–" + c.height() + " for the length you need.";
        }

        static List<Stitch> usedStitches(Chart c) {
            List<Stitch> used = new ArrayList<>();
            for (Stitch s : Stitch.values()) if (c.uses(s)) used.add(s);
            return used;
        }

        /** The whole pattern as plain lines (headings in capitals). Used for text export and printing. */
        static List<String> plainLines(Chart c) {
            List<String> l = new ArrayList<>();
            if (!c.description.isBlank()) { l.add(c.description); l.add(""); }
            l.add(castOn(c));
            l.add(readingNote(c));
            l.add("");
            if (c.isColourwork()) {
                l.add("YARNS");
                for (Yarn y : c.yarns) l.add("  Yarn " + y.letter() + ": " + y.name());
                l.add("");
            }
            l.add("KEY");
            for (Stitch s : usedStitches(c)) l.add("  " + s.symbol + "  " + s.description(c.inTheRound));
            l.add("");
            l.add("INSTRUCTIONS");
            for (String[] row : rows(c)) l.add(row[0] + ": " + row[1]);
            l.add(repeatLine(c));
            if (!c.notes.isBlank()) {
                l.add("");
                l.add("NOTES");
                l.addAll(Arrays.asList(c.notes.split("\n")));
            }
            List<String> warnings = Checks.run(c);
            if (!warnings.isEmpty()) {
                l.add("");
                l.add("PATTERN CHECKS");
                for (String w : warnings) l.add("  ! " + w);
            }
            return l;
        }
    }

    // =======================================================================
    // Pattern checks
    // =======================================================================

    static final class Checks {
        static final int LONG_FLOAT = 5;

        static List<String> run(Chart c) {
            List<String> out = new ArrayList<>();
            int w = c.width(), h = c.height();
            String row = c.rowWord(), rowLc = row.toLowerCase();

            // 1. Stitch counts must flow from one row to the next.
            int[] produced = new int[h], consumed = new int[h];
            for (int r = 0; r < h; r++)
                for (Stitch s : c.cells[r]) { produced[r] += s.produces; consumed[r] += s.consumes; }
            for (int r = 0; r < h; r++) {
                int prev = r == 0 ? h - 1 : r - 1;
                if (consumed[r] == produced[prev]) continue;
                if (r == 0)
                    out.add(String.format("%s 1 uses %d stitches, but the last %s (%s %d) leaves %d on the needle, "
                            + "so the repeat won't line up when you start again.", row, consumed[0], rowLc, rowLc, h, produced[h - 1]));
                else
                    out.add(String.format("%s %d uses %d stitches, but %s %d leaves %d on the needle.",
                            row, r + 1, consumed[r], rowLc, r, produced[prev]));
            }

            // 2. Flat charts need an even number of rows to keep sides consistent.
            if (!c.inTheRound && h % 2 == 1)
                out.add("The chart has an odd number of rows, so each new repeat would start on the opposite side of the work. "
                      + "Add or remove a row.");

            // 3. Stitches that belong on right-side rows.
            for (int r = 0; r < h; r++) {
                if (c.isRS(r)) continue;
                Set<Stitch> bad = new LinkedHashSet<>();
                for (Stitch s : c.cells[r]) if (s.rightSideOnly()) bad.add(s);
                for (Stitch s : bad)
                    out.add(String.format("Row %d is a wrong-side row, but %s is normally worked on right-side rows.", r + 1, s.label));
            }

            // 4. Cables need exactly the right number of squares.
            for (int r = 0; r < h; r++) {
                int col = 0;
                while (col < w) {
                    Stitch s = c.cells[r][col];
                    if (!s.isCable()) { col++; continue; }
                    int run = 1;
                    while (col + run < w && c.cells[r][col + run] == s) run++;
                    if (run % s.width != 0)
                        out.add(String.format("%s %d: a %s cable needs %d squares, but %d are marked.", row, r + 1, s.label, s.width, run));
                    col += run;
                }
            }

            // 5. Long floats in colourwork.
            if (c.isColourwork()) {
                for (int r = 0; r < h; r++) {
                    int[] yarnRow = new int[w];
                    Set<Integer> distinct = new HashSet<>();
                    for (int col = 0; col < w; col++) { yarnRow[col] = c.yarnIndex(r, col); distinct.add(yarnRow[col]); }
                    if (distinct.size() < 2) continue;
                    int best = 0, bestYarn = 0;
                    // Doubling the row handles runs that wrap across the repeat boundary.
                    int len = c.panel ? w : 2 * w, run = 0;
                    for (int i = 0; i < len; i++) {
                        int y = yarnRow[i % w];
                        run = (i > 0 && y == yarnRow[(i - 1) % w]) ? run + 1 : 1;
                        if (run > best) { best = Math.min(run, w); bestYarn = y; }
                    }
                    if (best > LONG_FLOAT)
                        out.add(String.format("%s %d: %d stitches in a row in yarn %s, so the other yarn floats across %d stitches. "
                                + "Catch the float about halfway along.", row, r + 1, best, c.yarns.get(bestYarn).letter(), best));
                }
            }
            return out;
        }
    }

    // =======================================================================
    // Size and yarn arithmetic
    // =======================================================================

    static final class Estimate {
        /** Approximate yarn length per stitch, as a multiple of the stitch width (stocking stitch). */
        static final double YARN_PER_STITCH_WIDTHS = 4.6;
        static final double SAFETY_MARGIN = 1.10;

        static int castOn(Chart c, double widthWanted, double stsPerUnit, int edges) {
            if (c.panel) return c.width();
            int reps = Math.max(1, (int) Math.round((widthWanted * stsPerUnit - edges) / c.width()));
            return reps * c.width() + edges;
        }

        static double fabricFactor(Chart c) {
            double f = 1.0;
            if (c.hasCables()) f *= 1.25;
            if (c.uses(Stitch.YO)) f *= 0.95;
            if (c.uses(Stitch.BOBBLE)) f *= 1.08;
            if (c.isColourwork()) f *= 1.5;
            return f;
        }

        /** Total yarn in metres for the given stitch and row counts. */
        static double metres(Chart c, int stitches, int rows, double stitchWidthMetres) {
            return stitches * (double) rows * stitchWidthMetres * YARN_PER_STITCH_WIDTHS * fabricFactor(c) * SAFETY_MARGIN;
        }

        /** Share of each yarn in the chart, used to split a colourwork estimate. */
        static double[] yarnShares(Chart c) {
            double[] share = new double[c.yarns.size()];
            int total = 0;
            for (int r = 0; r < c.height(); r++)
                for (int col = 0; col < c.width(); col++)
                    if (c.cells[r][col] != Stitch.NO_STITCH) { share[c.yarnIndex(r, col)]++; total++; }
            for (int i = 0; i < share.length; i++) share[i] = total == 0 ? 0 : share[i] / total;
            return share;
        }
    }

    // =======================================================================
    // Saving and loading (.knit files are plain text you can edit by hand)
    // =======================================================================

    static final class ChartIO {

        static String serialise(Chart c) {
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(APP_NAME).append(" pattern file\n");
            sb.append("# Chart rows are written top to bottom, exactly as the chart looks.\n");
            sb.append("# Symbols:");
            for (Stitch s : Stitch.values()) sb.append(' ').append(s.symbol).append('=').append(s.label);
            sb.append("\n\n");
            sb.append("name: ").append(esc(c.name)).append('\n');
            sb.append("description: ").append(esc(c.description)).append('\n');
            sb.append("notes: ").append(esc(c.notes)).append('\n');
            sb.append("in-the-round: ").append(c.inTheRound).append('\n');
            sb.append("panel: ").append(c.panel).append('\n');
            for (Yarn y : c.yarns)
                sb.append("yarn: ").append(y.letter()).append(" | ").append(esc(y.name())).append(" | ")
                  .append(Html.hex(y.color())).append('\n');
            sb.append("\nchart:\n");
            for (int r = c.height() - 1; r >= 0; r--) {
                for (Stitch s : c.cells[r]) sb.append(s.symbol);
                sb.append('\n');
            }
            if (c.isColourwork()) {
                sb.append("\ncolours:\n");
                for (int r = c.height() - 1; r >= 0; r--) {
                    for (int col = 0; col < c.width(); col++) sb.append(c.yarn(r, col).letter());
                    sb.append('\n');
                }
            }
            sb.append("\nend\n");
            return sb.toString();
        }

        static Chart parse(String text) {
            Map<String, String> fields = new LinkedHashMap<>();
            List<Yarn> yarns = new ArrayList<>();
            List<String> chart = new ArrayList<>(), colours = new ArrayList<>();
            List<String> section = null;
            int lineNo = 0;
            for (String raw : text.split("\r?\n")) {
                lineNo++;
                String line = raw.strip();
                if (line.startsWith("#") && section == null) continue;
                if (line.equalsIgnoreCase("chart:"))   { section = chart; continue; }
                if (line.equalsIgnoreCase("colours:") || line.equalsIgnoreCase("colors:")) { section = colours; continue; }
                if (line.equalsIgnoreCase("end"))      { section = null; continue; }
                if (section != null) {
                    if (!line.isEmpty()) section.add(line);
                    continue;
                }
                if (line.isEmpty()) continue;
                int colon = line.indexOf(':');
                if (colon < 0) throw new IllegalArgumentException("Line " + lineNo + " isn't understood: " + line);
                String key = line.substring(0, colon).strip().toLowerCase(), value = line.substring(colon + 1).strip();
                if (key.equals("yarn")) {
                    String[] p = value.split("\\|");
                    if (p.length < 3) throw new IllegalArgumentException("Line " + lineNo + ": a yarn needs a letter, a name and a colour.");
                    yarns.add(new Yarn(p[0].strip(), unesc(p[1].strip()), Color.decode(p[2].strip())));
                } else fields.put(key, unesc(value));
            }
            if (chart.isEmpty()) throw new IllegalArgumentException("The file has no chart section.");
            int w = chart.get(0).length(), h = chart.size();
            Chart c = new Chart(w, h);
            c.name = fields.getOrDefault("name", "Untitled pattern");
            c.description = fields.getOrDefault("description", "");
            c.notes = fields.getOrDefault("notes", "");
            c.inTheRound = Boolean.parseBoolean(fields.getOrDefault("in-the-round", "false"));
            c.panel = Boolean.parseBoolean(fields.getOrDefault("panel", "false"));
            if (!yarns.isEmpty()) { c.yarns.clear(); c.yarns.addAll(yarns); }
            if (!colours.isEmpty() && colours.size() != h)
                throw new IllegalArgumentException("The colours section must have the same number of rows as the chart.");
            for (int i = 0; i < h; i++) {
                String line = chart.get(i);
                if (line.length() != w) throw new IllegalArgumentException("Chart row " + (h - i) + " is a different width from the others.");
                for (int col = 0; col < w; col++) {
                    Stitch s = Stitch.fromSymbol(line.charAt(col));
                    if (s == null) throw new IllegalArgumentException("Unknown chart symbol '" + line.charAt(col) + "' in chart row " + (h - i) + ".");
                    c.cells[h - 1 - i][col] = s;
                    if (!colours.isEmpty()) {
                        String cl = colours.get(i);
                        if (cl.length() != w) throw new IllegalArgumentException("Colour row " + (h - i) + " is a different width from the chart.");
                        int idx = -1;
                        for (int y = 0; y < c.yarns.size(); y++) if (c.yarns.get(y).letter().equals(String.valueOf(cl.charAt(col)))) idx = y;
                        if (idx < 0) throw new IllegalArgumentException("Colour '" + cl.charAt(col) + "' has no matching yarn line.");
                        c.colours[h - 1 - i][col] = idx;
                    }
                }
            }
            return c;
        }

        static Chart load(Path p) throws IOException { return parse(Files.readString(p, StandardCharsets.UTF_8)); }

        static void save(Chart c, Path p) throws IOException { Files.writeString(p, serialise(c), StandardCharsets.UTF_8); }

        static String esc(String s)   { return s.replace("\\", "\\\\").replace("\n", "\\n"); }
        static String unesc(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\\' && i + 1 < s.length()) { char n = s.charAt(++i); sb.append(n == 'n' ? '\n' : n); }
                else sb.append(ch);
            }
            return sb.toString();
        }
    }

    // =======================================================================
    // Text output (terminal mode and "Export as text")
    // =======================================================================

    static final class TextOutput {
        static final String RESET = "\u001B[0m", BOLD = "\u001B[1m", DIM = "\u001B[2m", CYAN = "\u001B[36m",
                GREEN = "\u001B[32m", YELLOW = "\u001B[33m", RED = "\u001B[31m", MAGENTA = "\u001B[35m";
        final boolean ansi;

        TextOutput(boolean ansi) { this.ansi = ansi; }

        String a(String code, String s) { return ansi ? code + s + RESET : s; }
        String rgb(Color c, String s) {
            return ansi ? String.format("\u001B[38;2;%d;%d;%dm%s%s", c.getRed(), c.getGreen(), c.getBlue(), s, RESET) : s;
        }

        String banner() {
            String art = String.join("\n",
                "  ██╗  ██╗███╗   ██╗██╗████████╗████████╗██╗███╗   ██╗ ██████╗ ",
                "  ██║ ██╔╝████╗  ██║██║╚══██╔══╝╚══██╔══╝██║████╗  ██║██╔════╝ ",
                "  █████╔╝ ██╔██╗ ██║██║   ██║      ██║   ██║██╔██╗ ██║██║  ███╗",
                "  ██╔═██╗ ██║╚██╗██║██║   ██║      ██║   ██║██║╚██╗██║██║   ██║",
                "  ██║  ██╗██║ ╚████║██║   ██║      ██║   ██║██║ ╚████║╚██████╔╝",
                "  ╚═╝  ╚═╝╚═╝  ╚═══╝╚═╝   ╚═╝      ╚═╝   ╚═╝╚═╝  ╚═══╝ ╚═════╝ ",
                "                 P A T T E R N   S T U D I O");
            return "\n" + a(BOLD + CYAN, art) + "\n";
        }

        String symbol(Stitch s) {
            String ch = String.valueOf(s.symbol);
            switch (s) {
                case PURL: return a(CYAN, ch);
                case YO: return a(GREEN, ch);
                case K2TOG: case SSK: case SK2P: return a(RED, ch);
                case M1L: case M1R: case BOBBLE: return a(MAGENTA, ch);
                case SL1: case K1TBL: return a(BOLD, ch);
                case NO_STITCH: return a(DIM, ch);
                case KNIT: return ch;
                default: return a(YELLOW, ch);
            }
        }

        String cell(Chart c, int r, int col) {
            Stitch s = c.cells[r][col];
            if (c.isColourwork() && s == Stitch.KNIT) return ansi ? rgb(c.yarn(r, col).color(), "█") : c.yarn(r, col).letter();
            return symbol(s);
        }

        String chart(Chart c) {
            int w = c.width(), h = c.height();
            int across = c.panel ? 1 : Math.max(1, Math.min(4, 40 / w));
            int up = h < 8 ? Math.max(1, 8 / h) : 1;
            int cols = w * across, rows = h * up;
            String pad = "      ";
            StringBuilder sb = new StringBuilder();
            sb.append(pad).append('┌').append("─".repeat(cols)).append("┐\n");
            for (int dr = rows - 1; dr >= 0; dr--) {
                int r = dr % h;
                boolean rs = c.inTheRound || dr % 2 == 0;
                sb.append(rs ? pad : String.format("%5d ", dr + 1)).append('│');
                for (int dc = 0; dc < cols; dc++) sb.append(cell(c, r, dc % w));
                sb.append('│');
                if (rs) sb.append(' ').append(dr + 1);
                sb.append('\n');
            }
            sb.append(pad).append('└').append("─".repeat(cols)).append("┘\n");
            StringBuilder tens = new StringBuilder(pad + " "), units = new StringBuilder(pad + " ");
            for (int dc = 0; dc < cols; dc++) {
                int n = cols - dc;
                units.append(n % 10);
                tens.append(n >= 10 ? String.valueOf((n / 10) % 10) : " ");
            }
            if (cols >= 10) sb.append(a(DIM, tens.toString())).append('\n');
            sb.append(a(DIM, units.toString())).append('\n');
            sb.append(a(DIM, pad + "Row numbers on the right: read right to left. On the left: read left to right.")).append('\n');
            if (across > 1 || up > 1)
                sb.append(a(DIM, pad + "Showing the " + w + "-stitch × " + h + "-" + c.rowWord().toLowerCase()
                        + " repeat " + across + "× across and " + up + "× up.")).append('\n');
            return sb.toString();
        }

        String render(Chart c) {
            StringBuilder sb = new StringBuilder("\n");
            String title = c.name.toUpperCase();
            sb.append(a(BOLD, "╔" + "═".repeat(title.length() + 4) + "╗")).append('\n');
            sb.append(a(BOLD, "║  " + title + "  ║")).append('\n');
            sb.append(a(BOLD, "╚" + "═".repeat(title.length() + 4) + "╝")).append("\n\n");
            if (!c.description.isBlank()) sb.append(wrap(c.description, 78, "")).append("\n\n");
            sb.append(a(CYAN, wrap(Instructions.castOn(c), 78, ""))).append('\n');
            sb.append(a(CYAN, wrap(Instructions.readingNote(c), 78, ""))).append("\n\n");

            sb.append(a(BOLD, "KEY")).append('\n');
            if (c.isColourwork())
                for (Yarn y : c.yarns)
                    sb.append("  ").append(ansi ? rgb(y.color(), "█") : y.letter()).append("  Yarn ")
                      .append(y.letter()).append(": ").append(y.name()).append(", knit\n");
            for (Stitch s : Instructions.usedStitches(c)) {
                if (c.isColourwork() && s == Stitch.KNIT) continue;
                sb.append("  ").append(symbol(s)).append("  ").append(wrap(s.description(c.inTheRound), 74, "     ").strip()).append('\n');
            }
            sb.append('\n').append(chart(c)).append('\n');

            sb.append(a(BOLD + GREEN, "INSTRUCTIONS")).append('\n');
            for (String[] row : Instructions.rows(c)) {
                String lbl = String.format("%-14s", row[0] + ":");
                sb.append(a(BOLD, lbl)).append(wrap(row[1], 64, " ".repeat(14)).strip()).append('\n');
            }
            sb.append(Instructions.repeatLine(c)).append('\n');

            if (!c.notes.isBlank()) {
                sb.append('\n').append(a(BOLD, "NOTES")).append('\n');
                for (String p : c.notes.split("\n")) sb.append(wrap(p, 76, "  ")).append('\n');
            }
            List<String> warnings = Checks.run(c);
            sb.append('\n').append(a(BOLD, "PATTERN CHECKS")).append('\n');
            if (warnings.isEmpty()) sb.append(a(GREEN, "  ✓ Stitch counts, cables and floats all check out.")).append('\n');
            for (String w : warnings) sb.append(a(YELLOW, wrap("! " + w, 76, "  "))).append('\n');
            return sb.toString();
        }

        static String wrap(String text, int width, String indent) {
            StringBuilder out = new StringBuilder(), line = new StringBuilder();
            for (String word : text.split(" ")) {
                if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                    out.append(indent).append(line).append('\n');
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            if (line.length() > 0) out.append(indent).append(line);
            return out.toString();
        }
    }

    // =======================================================================
    // Drawing charts and symbols
    // =======================================================================

    static final class Renderer {
        static final Color PAPER     = new Color(0xFFFDF8);
        static final Color GRID      = new Color(0xA79D94);
        static final Color INK       = new Color(0x2E2A27);
        static final Color LIGHT_INK = new Color(0xF7F3EC);
        static final Color NO_ST     = new Color(0xC9C3BC);
        static final Color REPEAT    = new Color(0xC0392B);
        static final Color HILITE    = new Color(255, 205, 40, 90);
        static final Color HILITE_EDGE = new Color(0xE0A800);
        static final Color STITCH_HILITE = new Color(255, 160, 0, 110);
        static final Color ACCENT    = new Color(0x7A3E2E);

        static Color inkFor(Color fill) {
            double lum = 0.299 * fill.getRed() + 0.587 * fill.getGreen() + 0.114 * fill.getBlue();
            return lum < 140 ? LIGHT_INK : INK;
        }

        static Color mix(Color a, Color b, double t) {
            return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                             (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                             (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
        }

        static void smooth(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        }

        /** Draws the symbol for a single-square stitch. */
        static void glyph(Graphics2D g, Stitch s, double x, double y, double cs, Color ink) {
            g.setColor(ink);
            g.setStroke(new BasicStroke((float) Math.max(1.4, cs / 11.0), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double p = cs * 0.22;
            switch (s) {
                case PURL -> { double d = cs * 0.26; g.fill(new Ellipse2D.Double(x + cs / 2 - d / 2, y + cs / 2 - d / 2, d, d)); }
                case YO -> { double d = cs - 2 * p; g.draw(new Ellipse2D.Double(x + p, y + p, d, d)); }
                case K2TOG -> g.draw(new Line2D.Double(x + p, y + cs - p, x + cs - p, y + p));
                case SSK -> g.draw(new Line2D.Double(x + p, y + p, x + cs - p, y + cs - p));
                case SK2P -> {
                    Path2D path = new Path2D.Double();
                    path.moveTo(x + p, y + cs - p); path.lineTo(x + cs / 2, y + p); path.lineTo(x + cs - p, y + cs - p);
                    g.draw(path);
                    g.draw(new Line2D.Double(x + cs / 2, y + p, x + cs / 2, y + cs - p));
                }
                case SL1 -> {   // a V: the slipped stitch is stretched up through the row
                    Path2D v = new Path2D.Double();
                    v.moveTo(x + p, y + p); v.lineTo(x + cs / 2, y + cs - p); v.lineTo(x + cs - p, y + p);
                    g.draw(v);
                }
                case K1TBL -> {  // a little loop with crossed legs: a twisted stitch
                    double t = cs * 0.30, b = cs - p;
                    g.draw(new Line2D.Double(x + cs * 0.30, y + b, x + cs * 0.64, y + cs * 0.42));
                    g.draw(new Line2D.Double(x + cs * 0.70, y + b, x + cs * 0.36, y + cs * 0.42));
                    g.draw(new Arc2D.Double(x + cs * 0.34, y + p, cs * 0.32, t + cs * 0.06, 0, 180, Arc2D.OPEN));
                    g.draw(new Line2D.Double(x + cs * 0.34, y + p + (t + cs * 0.06) / 2, x + cs * 0.36, y + cs * 0.42));
                    g.draw(new Line2D.Double(x + cs * 0.66, y + p + (t + cs * 0.06) / 2, x + cs * 0.64, y + cs * 0.42));
                }
                case BOBBLE -> g.fill(star(x + cs / 2, y + cs / 2, cs * 0.36, cs * 0.16));
                case M1L, M1R -> {
                    String t = s == Stitch.M1L ? "ML" : "MR";
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.max(7, cs * 0.4)));
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(t, (float) (x + (cs - fm.stringWidth(t)) / 2), (float) (y + (cs + fm.getAscent() - fm.getDescent()) / 2));
                }
                default -> { }
            }
        }

        /**
         * Draws a small picture of how a single-square stitch looks in the knitted
         * fabric (from the right side): a knit stitch is a V, a purl stitch a
         * horizontal bump, a yarn over a little eyelet, decreases are stitches
         * leaning over one another, and increases are twisted stitches.
         */
        static void stitchPicture(Graphics2D g, Stitch s, double x, double y, double cs, Color fill) {
            stitchPicture(g, s, x, y, cs, fill, 1.0);
        }

        /** As above; a lower contrast (0 to 1) gives softer outlines, so tiny swatches still show their colours. */
        static void stitchPicture(Graphics2D g, Stitch s, double x, double y, double cs, Color fill, double contrast) {
            if (s == Stitch.NO_STITCH || s.isCable()) return;
            Color ink = inkFor(fill);
            Color body = mix(fill, ink, 0.12 * contrast), shade = mix(fill, ink, 0.30 * contrast), edge = mix(fill, ink, 0.62 * contrast);
            float line = (float) Math.max(0.7, cs / 24.0);
            double cx = x + cs / 2, base = y + cs * 0.90, len = cs * 0.80, wid = cs * 0.30;
            switch (s) {
                case KNIT -> {
                    petal(g, cx, base, len, wid, -24, body, edge, line);
                    petal(g, cx, base, len, wid, 24, body, edge, line);
                }
                case PURL -> {
                    Ellipse2D bump = new Ellipse2D.Double(x + cs * 0.08, y + cs * 0.32, cs * 0.84, cs * 0.36);
                    g.setColor(body); g.fill(bump);
                    g.setColor(edge); g.setStroke(new BasicStroke(line)); g.draw(bump);
                    // A soft highlight along the top of the bump.
                    g.setColor(mix(fill, Color.WHITE, 0.35));
                    g.draw(new Arc2D.Double(x + cs * 0.18, y + cs * 0.38, cs * 0.64, cs * 0.20, 20, 140, Arc2D.OPEN));
                }
                case YO -> {
                    double d = cs * 0.62, h = cs * 0.30;
                    Ellipse2D ring = new Ellipse2D.Double(cx - d / 2, y + cs / 2 - d / 2, d, d);
                    g.setColor(body); g.fill(ring);
                    g.setColor(edge); g.setStroke(new BasicStroke(line)); g.draw(ring);
                    g.setColor(mix(fill, INK, 0.70));
                    g.fill(new Ellipse2D.Double(cx - h / 2, y + cs / 2 - h / 2, h, h));
                }
                case K2TOG -> {   // the left stitch lies on top, leaning right
                    petal(g, x + cs * 0.32, base, len, wid, 16, shade, edge, line);
                    petal(g, x + cs * 0.32, base, len, wid, 42, body, edge, line);
                }
                case SSK -> {     // the right stitch lies on top, leaning left
                    petal(g, x + cs * 0.68, base, len, wid, -16, shade, edge, line);
                    petal(g, x + cs * 0.68, base, len, wid, -42, body, edge, line);
                }
                case SK2P -> {    // three stitches gathered into one, the centre one on top
                    petal(g, cx, base, len * 0.88, wid, -38, shade, edge, line);
                    petal(g, cx, base, len * 0.88, wid, 38, shade, edge, line);
                    petal(g, cx, base, len, wid, 0, body, edge, line);
                }
                case SL1 -> {     // a slipped stitch is pulled up long and thin
                    petal(g, cx, y + cs * 0.97, cs * 0.92, wid * 0.72, -11, body, edge, line);
                    petal(g, cx, y + cs * 0.97, cs * 0.92, wid * 0.72, 11, body, edge, line);
                }
                case K1TBL -> {   // a twisted stitch: its legs cross at the bottom
                    double off = cs * 0.07;
                    petal(g, cx + off, base, len * 0.92, wid, -22, body, edge, line);
                    petal(g, cx - off, base, len * 0.92, wid, 22, body, edge, line);
                }
                case BOBBLE -> {  // a round knob standing proud of the fabric
                    double d = cs * 0.78;
                    Ellipse2D ball = new Ellipse2D.Double(cx - d / 2, y + cs / 2 - d / 2, d, d);
                    g.setColor(shade); g.fill(ball);
                    g.setColor(body); g.fill(new Ellipse2D.Double(cx - d * 0.42, y + cs / 2 - d * 0.44, d * 0.72, d * 0.72));
                    g.setColor(mix(fill, Color.WHITE, 0.55));
                    g.fill(new Ellipse2D.Double(cx - d * 0.26, y + cs / 2 - d * 0.30, d * 0.24, d * 0.18));
                    g.setColor(edge); g.setStroke(new BasicStroke(line)); g.draw(ball);
                }
                case M1L, M1R -> { // a twisted stitch: its legs cross at the bottom
                    double lean = s == Stitch.M1L ? -10 : 10, off = cs * 0.07;
                    petal(g, cx + off, base, len * 0.92, wid, -22 + lean, body, edge, line);
                    petal(g, cx - off, base, len * 0.92, wid, 22 + lean, body, edge, line);
                    // A tiny arrow shows which way the increase leans.
                    double ax = s == Stitch.M1L ? x + cs * 0.20 : x + cs * 0.80, ay = y + cs * 0.20, a = cs * 0.10;
                    double dir = s == Stitch.M1L ? 1 : -1;
                    g.setColor(edge);
                    g.setStroke(new BasicStroke(line, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D arrow = new Path2D.Double();
                    arrow.moveTo(ax + dir * a, ay - a); arrow.lineTo(ax, ay); arrow.lineTo(ax + dir * a, ay + a);
                    g.draw(arrow);
                }
                default -> { }
            }
        }

        /** One leg of a stitch: an elongated oval standing on (px, py), tilted by deg (positive leans right). */
        static void petal(Graphics2D g, double px, double py, double len, double wid, double deg, Color body, Color edge, float line) {
            AffineTransform old = g.getTransform();
            g.translate(px, py);
            g.rotate(Math.toRadians(deg));
            Ellipse2D e = new Ellipse2D.Double(-wid / 2, -len, wid, len);
            g.setColor(body); g.fill(e);
            g.setColor(edge); g.setStroke(new BasicStroke(line)); g.draw(e);
            g.setTransform(old);
        }

        /** Draws a single-square stitch either as a chart symbol or as a picture of the stitch. */
        static void stitch(Graphics2D g, Stitch s, double x, double y, double cs, Color fill, boolean pictures) {
            if (pictures) stitchPicture(g, s, x, y, cs, fill);
            else glyph(g, s, x, y, cs, inkFor(fill));
        }

        /**
         * Paints the stitches and cables of a chart, tiled into a block of cols × rows
         * squares whose top-left corner is (x, y). Display row 0 is the bottom row.
         */
        static void tile(Graphics2D g, Chart c, double x, double y, double cs, int cols, int rows, boolean pictures) {
            tile(g, c, x, y, cs, cols, rows, pictures, false);
        }

        /**
         * With {@code swatch} set, the stitches are drawn softly and each cable crossing is
         * stretched over three rows, so a tiny swatch still looks like the fabric.
         */
        static void tile(Graphics2D g, Chart c, double x, double y, double cs, int cols, int rows, boolean pictures, boolean swatch) {
            int w = c.width(), h = c.height();
            for (int dr = 0; dr < rows; dr++) {
                int r = dr % h;
                double sy = y + (rows - 1 - dr) * cs;
                for (int dc = 0; dc < cols; dc++) {
                    int col = dc % w;
                    Stitch s = c.cells[r][col];
                    Color fill = s == Stitch.NO_STITCH ? NO_ST : c.fill(r, col);
                    g.setColor(fill);
                    g.fill(new Rectangle2D.Double(x + dc * cs, sy, cs + 0.5, cs + 0.5));
                    if (s.isCable() && swatch) stitchPicture(g, Stitch.KNIT, x + dc * cs, sy, cs, fill, 0.5);
                    else if (!s.isCable()) {
                        if (swatch) stitchPicture(g, s, x + dc * cs, sy, cs, fill, c.isColourwork() ? 0.35 : 0.6);
                        else stitch(g, s, x + dc * cs, sy, cs, fill, pictures);
                    }
                }
            }
            // Cables span several squares; draw each crossing once, across repeats.
            for (int dr = 0; dr < rows; dr++) {
                int r = dr % h;
                double sy = y + (rows - 1 - dr) * cs, ch = swatch ? 3 * cs : cs;
                if (swatch) sy -= cs;
                for (int start = 0; start < cols; start += w) {
                    int col = 0;
                    while (col < w) {
                        Stitch s = c.cells[r][col];
                        if (!s.isCable()) { col++; continue; }
                        int run = 1;
                        while (col + run < w && c.cells[r][col + run] == s) run++;
                        for (int u = 0; u < run / s.width; u++) {
                            int dc = start + col + u * s.width;
                            if (dc < cols) cable(g, x + dc * cs, sy, s.width * cs, ch, s, c.fill(r, col), swatch);
                        }
                        col += run;
                    }
                }
            }
        }

        /** A little swatch showing the fabric a chart will knit, for the pattern list. Drawn as vectors so it stays sharp. */
        static Icon thumbnail(Chart c, int size) {
            return new Icon() {
                public void paintIcon(Component comp, Graphics g0, int x, int y) {
                    Graphics2D g = (Graphics2D) g0.create();
                    smooth(g);
                    int w = c.width(), h = c.height();
                    // Small charts show at least two repeats; large ones show a close-up of one.
                    double cs = Math.max(4, Math.min(9, Math.floor(size / (double) (w <= 6 ? 2 * w : w))));
                    int cols = (int) Math.ceil(size / cs), rows = cols;
                    Shape round = new RoundRectangle2D.Double(x, y, size, size, 8, 8);
                    Shape oldClip = g.getClip();
                    g.clip(round);
                    g.setColor(PAPER);
                    g.fill(round);
                    tile(g, c, x, y + size - rows * cs, cs, cols, rows, true, true);
                    g.setClip(oldClip);
                    g.setColor(GRID);
                    g.setStroke(new BasicStroke(1f));
                    g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, size - 1, size - 1, 8, 8));
                    g.dispose();
                }
                public int getIconWidth()  { return size; }
                public int getIconHeight() { return size; }
            };
        }

        /** A five-pointed star centred on (cx, cy). */
        static Shape star(double cx, double cy, double outer, double inner) {
            Path2D p = new Path2D.Double();
            for (int i = 0; i < 10; i++) {
                double r = i % 2 == 0 ? outer : inner, a = -Math.PI / 2 + i * Math.PI / 5;
                if (i == 0) p.moveTo(cx + r * Math.cos(a), cy + r * Math.sin(a));
                else p.lineTo(cx + r * Math.cos(a), cy + r * Math.sin(a));
            }
            p.closePath();
            return p;
        }

        /** Draws a cable crossing filling the rectangle (x, y, w, h). */
        /** Draws the crossing for a cable or travelling stitch. */
        static void cable(Graphics2D g, double x, double y, double w, double h, Stitch s, Color fill, boolean soft) {
            cable(g, x, y, w, h, s.frontCross(), s.travels(), fill, soft);
        }

        /** With {@code soft} set, only the strands are drawn, in gentler tones, for small fabric swatches. */
        static void cable(Graphics2D g, double x, double y, double w, double h, boolean front, boolean travel, Color fill, boolean soft) {
            Color ink = soft ? mix(fill, inkFor(fill), 0.45) : inkFor(fill);
            Rectangle2D box = new Rectangle2D.Double(x, y, w, h);
            if (!soft) {
                g.setColor(mix(fill, ink, 0.06));
                g.fill(box);
            }
            // Each strand is an S-curve from the middle of one half at the bottom to the middle of the other half at the top.
            double q = w / 4, band = Math.min(h * 0.55, w * 0.22), edge = Math.max(1.2, h / 14.0);
            Path2D rising = new Path2D.Double();   // bottom-left to top-right
            rising.moveTo(x + q, y + h);
            rising.curveTo(x + q, y + h * 0.4, x + 3 * q, y + h * 0.6, x + 3 * q, y);
            Path2D falling = new Path2D.Double();  // bottom-right to top-left
            falling.moveTo(x + 3 * q, y + h);
            falling.curveTo(x + 3 * q, y + h * 0.4, x + q, y + h * 0.6, x + q, y);
            // A front cross leans left as it rises, so the falling strand is on top.
            Path2D back = front ? rising : falling, top = front ? falling : rising;
            Shape oldClip = g.getClip();
            g.clip(box);
            BasicStroke outer = new BasicStroke((float) (band + 2 * edge), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND),
                        inner = new BasicStroke((float) band, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
            if (travel) {
                // The stitches behind are purls: show purl dots where they start and finish, instead of a strand.
                double d = Math.max(2, Math.min(h, w / 2) * 0.26);
                double bx = front ? x + q : x + 3 * q, ex = front ? x + 3 * q : x + q;
                g.setColor(soft ? mix(fill, ink, 0.6) : ink);
                g.fill(new Ellipse2D.Double(bx - d / 2, y + h * 0.78 - d / 2, d, d));
                g.fill(new Ellipse2D.Double(ex - d / 2, y + h * 0.22 - d / 2, d, d));
            } else {
                g.setStroke(outer); g.setColor(ink);                   g.draw(back);
                g.setStroke(inner); g.setColor(mix(fill, ink, 0.35));  g.draw(back);
            }
            g.setStroke(outer); g.setColor(ink);                   g.draw(top);
            g.setStroke(inner); g.setColor(fill);                  g.draw(top);
            g.setClip(oldClip);
            if (soft) return;
            g.setStroke(new BasicStroke(1f));
            g.setColor(ink);
            g.draw(box);
        }

        /** A small picture of a stitch for keys and buttons. Cables use narrower squares to stay compact. */
        static BufferedImage icon(Stitch s, int height, int scale) { return icon(s, height, scale, false); }

        static BufferedImage icon(Stitch s, int height, int scale, boolean pictures) {
            int cell = height, w = s.isCable() ? s.width * height / 2 : height;
            BufferedImage img = new BufferedImage(w * scale, cell * scale, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            smooth(g);
            g.scale(scale, scale);
            if (s.isCable()) cable(g, 0.5, 0.5, w - 1, cell - 1, s, PAPER, false);
            else {
                g.setColor(s == Stitch.NO_STITCH ? NO_ST : PAPER);
                g.fill(new Rectangle2D.Double(0, 0, w, cell));
                stitch(g, s, 0, 0, cell, PAPER, pictures);
                g.setColor(GRID);
                g.setStroke(new BasicStroke(1f));
                g.draw(new Rectangle2D.Double(0.5, 0.5, w - 1, cell - 1));
            }
            g.dispose();
            return img;
        }

        static Icon swatch(Color c, int size) {
            return new Icon() {
                public void paintIcon(Component comp, Graphics g0, int x, int y) {
                    Graphics2D g = (Graphics2D) g0.create();
                    smooth(g);
                    g.setColor(c); g.fillRoundRect(x, y, size, size, 4, 4);
                    g.setColor(GRID); g.drawRoundRect(x, y, size - 1, size - 1, 4, 4);
                    g.dispose();
                }
                public int getIconWidth()  { return size; }
                public int getIconHeight() { return size; }
            };
        }
    }

    /** The layout and painting of a chart with repeats, row and column numbers. Shared by screen, export and print. */
    static final class ChartView {
        final Chart c;
        final int across, up, cs, left, right, top = 12, bottom = 30;
        /** True to draw pictures of the stitches instead of standard chart symbols (screen only). */
        final boolean pictures;

        ChartView(Chart c, int across, int up, int cs) { this(c, across, up, cs, false); }

        ChartView(Chart c, int across, int up, int cs, boolean pictures) {
            this.c = c;
            this.pictures = pictures;
            this.across = c.panel ? 1 : Math.max(1, across);
            this.up = Math.max(1, up);
            this.cs = cs;
            this.left = 18 + Math.max(16, cs);
            this.right = this.left;
        }

        int cols() { return c.width() * across; }
        int rows() { return c.height() * up; }
        Dimension size() { return new Dimension(left + cols() * cs + right, top + rows() * cs + bottom); }
        int cellX(int dc) { return left + dc * cs; }
        int cellY(int dr) { return top + (rows() - 1 - dr) * cs; }

        /** Returns {displayRow, displayCol} under a point, or null. Display row 0 is the bottom row. */
        int[] cellAt(int x, int y) {
            if (x < left || y < top) return null;
            int dc = (x - left) / cs, fromTop = (y - top) / cs;
            if (dc >= cols() || fromTop >= rows()) return null;
            return new int[] { rows() - 1 - fromTop, dc };
        }

        void paint(Graphics2D g, int highlightRow) { paint(g, highlightRow, null); }

        /** {@code stitch} is {leftmost column, squares covered} of the stitch to pick out in the highlighted row, or null. */
        void paint(Graphics2D g, int highlightRow, int[] stitch) {
            Renderer.smooth(g);
            int w = c.width(), h = c.height(), cols = cols(), rows = rows();

            for (int dr = 0; dr < rows; dr++) {
                int r = dr % h, y = cellY(dr);
                for (int dc = 0; dc < cols; dc++) {
                    int col = dc % w, x = cellX(dc);
                    Stitch s = c.cells[r][col];
                    Color fill = s == Stitch.NO_STITCH ? Renderer.NO_ST : c.fill(r, col);
                    g.setColor(fill);
                    g.fillRect(x, y, cs, cs);
                    if (!s.isCable()) Renderer.stitch(g, s, x, y, cs, fill, pictures);
                }
            }

            if (highlightRow >= 0 && highlightRow < h) {
                g.setColor(Renderer.HILITE);
                for (int dr = 0; dr < rows; dr++) if (dr % h == highlightRow) g.fillRect(left, cellY(dr), cols * cs, cs);
            }

            g.setStroke(new BasicStroke(1f));
            g.setColor(Renderer.GRID);
            for (int i = 0; i <= cols; i++) g.drawLine(left + i * cs, top, left + i * cs, top + rows * cs);
            for (int j = 0; j <= rows; j++) g.drawLine(left, top + j * cs, left + cols * cs, top + j * cs);

            for (int dr = 0; dr < rows; dr++) {
                int r = dr % h;
                for (int a = 0; a < across; a++) {
                    int col = 0;
                    while (col < w) {
                        Stitch s = c.cells[r][col];
                        if (!s.isCable()) { col++; continue; }
                        int run = 1;
                        while (col + run < w && c.cells[r][col + run] == s) run++;
                        for (int u = 0; u < run / s.width; u++)
                            Renderer.cable(g, cellX(a * w + col + u * s.width), cellY(dr), s.width * cs, cs, s, c.fill(r, col), false);
                        col += run;
                    }
                }
            }

            if (highlightRow >= 0 && highlightRow < h) {
                g.setColor(Renderer.HILITE_EDGE);
                g.setStroke(new BasicStroke(2f));
                for (int dr = 0; dr < rows; dr++) if (dr % h == highlightRow) g.drawRect(left, cellY(dr), cols * cs, cs);
                if (stitch != null) {
                    for (int dr = 0; dr < rows; dr++) {
                        if (dr % h != highlightRow) continue;
                        for (int a = 0; a < across; a++) {
                            int sx = cellX(a * w + stitch[0]), sy = cellY(dr), sw = stitch[1] * cs;
                            g.setColor(Renderer.STITCH_HILITE);
                            g.fillRect(sx, sy, sw, cs);
                            g.setColor(Renderer.ACCENT);
                            g.setStroke(new BasicStroke(3f));
                            g.drawRect(sx - 1, sy - 1, sw + 2, cs + 2);
                        }
                    }
                }
            }

            g.setColor(Renderer.INK);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(left, top, cols * cs, rows * cs);

            if (across > 1 || up > 1) {
                g.setColor(Renderer.REPEAT);
                g.setStroke(new BasicStroke(2.5f));
                g.drawRect(cellX((across - 1) * w), cellY(h - 1), w * cs, h * cs);
            }

            Font font = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(9, Math.min(13, cs / 2 + 1)));
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(Renderer.INK);
            for (int dr = 0; dr < rows; dr++) {
                String t = String.valueOf(dr + 1);
                boolean rs = c.inTheRound || dr % 2 == 0;
                int by = cellY(dr) + (cs + fm.getAscent() - fm.getDescent()) / 2;
                if (rs) g.drawString(t, left + cols * cs + 6, by);
                else    g.drawString(t, left - 6 - fm.stringWidth(t), by);
            }
            g.setColor(Renderer.mix(Renderer.INK, Renderer.PAPER, 0.35));
            for (int dc = 0; dc < cols; dc++) {
                int n = cols - dc;
                if (cols > 40 && n % 5 != 0 && n != 1) continue;
                if (cs < 16 && n % 5 != 0 && n != 1) continue;
                String t = String.valueOf(n);
                g.drawString(t, cellX(dc) + (cs - fm.stringWidth(t)) / 2, top + rows * cs + fm.getAscent() + 5);
            }
        }

        BufferedImage image(int scale) {
            Dimension d = size();
            BufferedImage img = new BufferedImage(d.width * scale, d.height * scale, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.scale(scale, scale);
            paint(g, -1);
            g.dispose();
            return img;
        }
    }

    // =======================================================================
    // HTML (instructions panel, web-page export)
    // =======================================================================

    static final class Html {
        static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
        static String hex(Color c)  { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

        static final String SWING_CSS =
              "body { font-family: SansSerif; font-size: 12pt; margin: 10px; color: #2E2A27; }"
            + "h1 { font-size: 17pt; color: #7A3E2E; margin-top: 0; margin-bottom: 4px; }"
            + "h2 { font-size: 13pt; color: #7A3E2E; margin-top: 14px; margin-bottom: 4px; }"
            + "p { margin-top: 3px; margin-bottom: 6px; }"
            + "td { padding: 3px; vertical-align: top; }"
            + ".lbl { color: #7A3E2E; }"
            + ".warn { color: #9A5B00; }"
            + ".muted { color: #6E6660; }";

        static final String BROWSER_CSS =
              "body{font-family:Georgia,'Times New Roman',serif;max-width:860px;margin:2rem auto;padding:0 1rem;"
            + "color:#2E2A27;background:#FFFDF8;line-height:1.5}"
            + "h1{color:#7A3E2E;margin-bottom:.2rem}h2{color:#7A3E2E;border-bottom:1px solid #E6DED4;padding-bottom:.2rem;margin-top:2rem}"
            + "td{padding:.25rem .6rem .25rem 0;vertical-align:top}.lbl{color:#7A3E2E;white-space:nowrap}"
            + "img.chart{max-width:100%;border:1px solid #E6DED4;background:#fff}.warn{color:#9A5B00}.muted{color:#6E6660}"
            + "@media print{body{background:#fff;margin:0}h2{break-after:avoid}img.chart{break-inside:avoid}}";

        static String pattern(Chart c, Function<Stitch, String> iconSrc, String chartSrc, boolean browser) {
            StringBuilder h = new StringBuilder("<html><head>");
            if (browser) h.append("<meta charset='utf-8'><meta name='viewport' content='width=device-width'><title>")
                          .append(esc(c.name)).append("</title><style>").append(BROWSER_CSS).append("</style>");
            else h.append("<style>").append(SWING_CSS).append("</style>");
            h.append("</head><body>");
            h.append("<h1>").append(esc(c.name)).append("</h1>");
            if (!c.description.isBlank()) h.append("<p>").append(esc(c.description)).append("</p>");
            h.append("<p class='muted'>").append(esc(Instructions.castOn(c))).append(' ')
             .append(esc(Instructions.readingNote(c))).append("</p>");

            if (c.isColourwork()) {
                h.append("<h2>Yarns</h2><table>");
                for (Yarn y : c.yarns)
                    h.append("<tr><td bgcolor='").append(hex(y.color())).append("' width='26'>&nbsp;</td><td><b>Yarn ")
                     .append(esc(y.letter())).append("</b>: ").append(esc(y.name())).append("</td></tr>");
                h.append("</table>");
            }
            if (chartSrc != null) h.append("<h2>Chart</h2><p><img class='chart' src='").append(chartSrc).append("' alt='Chart'></p>");

            h.append("<h2>Key</h2><table>");
            for (Stitch s : Instructions.usedStitches(c)) {
                int iw = s.isCable() ? s.width * 11 : 22;
                h.append("<tr><td><img src='").append(iconSrc.apply(s)).append("' width='").append(iw)
                 .append("' height='22' alt='").append(esc(s.label)).append("'></td><td>")
                 .append(esc(s.description(c.inTheRound))).append("</td></tr>");
            }
            h.append("</table>");

            h.append("<h2>Instructions</h2><table>");
            for (String[] row : Instructions.rows(c))
                h.append("<tr><td class='lbl' nowrap><b>").append(esc(row[0])).append("</b></td><td>")
                 .append(esc(row[1])).append("</td></tr>");
            h.append("</table><p>").append(esc(Instructions.repeatLine(c))).append("</p>");

            if (!c.notes.isBlank()) {
                h.append("<h2>Notes</h2>");
                for (String p : c.notes.split("\n")) h.append("<p>").append(esc(p)).append("</p>");
            }
            if (browser) {
                List<String> warnings = Checks.run(c);
                if (!warnings.isEmpty()) {
                    h.append("<h2>Pattern checks</h2><ul>");
                    for (String w : warnings) h.append("<li class='warn'>").append(esc(w)).append("</li>");
                    h.append("</ul>");
                }
                h.append("<p class='muted'><small>Made with ").append(APP_NAME).append(".</small></p>");
            }
            return h.append("</body></html>").toString();
        }

        static String dataUri(BufferedImage img) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageIO.write(img, "png", bytes);
                return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
            } catch (IOException e) {
                return "";
            }
        }

        /** Swing's HTML viewer can't show data: images, so key icons are written to temporary files once. */
        static final Map<String, String> ICON_FILES = new HashMap<>();
        static Path iconDir;

        /** The key icon as a chart symbol, or as a stitch picture to match the chart on screen. */
        static String iconFile(Stitch s, boolean pictures) {
            String name = s.name() + (pictures ? "-picture" : "");
            return ICON_FILES.computeIfAbsent(name, k -> {
                try {
                    if (iconDir == null) { iconDir = Files.createTempDirectory("knitting-icons"); iconDir.toFile().deleteOnExit(); }
                    File f = iconDir.resolve(name + ".png").toFile();
                    ImageIO.write(Renderer.icon(s, 22, 1, pictures), "png", f);
                    f.deleteOnExit();
                    return f.toURI().toURL().toString();
                } catch (IOException e) {
                    return "";
                }
            });
        }
    }

    // =======================================================================
    // Printing
    // =======================================================================

    static final class PatternPrintable implements Printable {
        /** The chart image is rendered at this multiple and drawn smaller, so it stays sharp on paper. */
        static final int IMAGE_SCALE = 3;
        final Chart chart;
        final BufferedImage image;
        final List<String> source;
        List<List<String>> pages;
        final Font body = new Font(Font.SERIF, Font.PLAIN, 10), bold = new Font(Font.SERIF, Font.BOLD, 11),
                   title = new Font(Font.SERIF, Font.BOLD, 16);

        PatternPrintable(Chart chart, BufferedImage image) {
            this.chart = chart; this.image = image; this.source = Instructions.plainLines(chart);
        }

        static boolean isHeading(String s) {
            return !s.isBlank() && s.equals(s.toUpperCase()) && s.chars().anyMatch(Character::isLetter) && !s.startsWith(" ");
        }

        void layout(Graphics2D g, double width, double height) {
            FontMetrics fm = g.getFontMetrics(body);
            int lineH = fm.getHeight() + 1, perPage = Math.max(1, (int) (height / lineH));
            List<String> lines = new ArrayList<>();
            for (String s : source) {
                if (s.isEmpty()) { lines.add(""); continue; }
                String indent = s.startsWith("  ") ? "      " : "";
                StringBuilder line = new StringBuilder();
                for (String word : s.split(" ")) {
                    String trial = line.length() == 0 ? word : line + " " + word;
                    if (fm.stringWidth(trial) > width && line.length() > 0) { lines.add(line.toString()); line = new StringBuilder(indent + word); }
                    else line = new StringBuilder(trial);
                }
                lines.add(line.toString());
            }
            pages = new ArrayList<>();
            for (int i = 0; i < lines.size(); i += perPage) pages.add(lines.subList(i, Math.min(lines.size(), i + perPage)));
        }

        @Override
        public int print(Graphics g0, PageFormat pf, int page) {
            Graphics2D g = (Graphics2D) g0;
            Renderer.smooth(g);
            g.translate(pf.getImageableX(), pf.getImageableY());
            double w = pf.getImageableWidth(), h = pf.getImageableHeight();
            if (pages == null) layout(g, w, h);
            if (page > pages.size()) return NO_SUCH_PAGE;
            g.setColor(Color.BLACK);
            if (page == 0) {
                g.setFont(title);
                g.drawString(chart.name, 0, 16);
                double topY = 30, scale = Math.min(1.0 / IMAGE_SCALE, Math.min(w / image.getWidth(), (h - topY) / image.getHeight()));
                g.drawImage(image, new AffineTransform(scale, 0, 0, scale, 0, topY), null);
                return PAGE_EXISTS;
            }
            int y = g.getFontMetrics(body).getAscent();
            for (String line : pages.get(page - 1)) {
                g.setFont(isHeading(line) ? bold : body);
                g.drawString(line, 0, y);
                y += g.getFontMetrics(body).getHeight() + 1;
            }
            return PAGE_EXISTS;
        }
    }

    // =======================================================================
    // Swing GUI
    // =======================================================================

    /** The interactive chart: zoom, repeats, hover tooltips, highlighted row and painting in edit mode. */
    static final class ChartPanel extends JPanel {
        Chart chart;
        int across = 2, up = 1, cs = 24, highlight = -1;
        int[] highlightStitch;   // {leftmost column, squares} in the highlighted row, or null
        boolean editing, pictures = true;
        BiConsumer<int[], Boolean> painter;   // {chartRow, chartCol}, true when a new stroke starts
        private int[] lastCell;

        ChartPanel() {
            setBackground(Color.WHITE);
            ToolTipManager.sharedInstance().registerComponent(this);
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e)  { if (SwingUtilities.isLeftMouseButton(e)) paintAt(e, true); }
                @Override public void mouseDragged(MouseEvent e)  { if (SwingUtilities.isLeftMouseButton(e)) paintAt(e, false); }
                @Override public void mouseReleased(MouseEvent e) { lastCell = null; }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        ChartView view() { return new ChartView(chart, across, up, cs, pictures); }

        void paintAt(MouseEvent e, boolean start) {
            if (!editing || chart == null || painter == null) return;
            int[] cell = view().cellAt(e.getX(), e.getY());
            if (cell == null) return;
            int[] chartCell = { cell[0] % chart.height(), cell[1] % chart.width() };
            if (!start && Arrays.equals(chartCell, lastCell)) return;
            lastCell = chartCell;
            painter.accept(chartCell, start);
        }

        @Override public Dimension getPreferredSize() { return chart == null ? new Dimension(400, 300) : view().size(); }

        @Override public String getToolTipText(MouseEvent e) {
            if (chart == null) return null;
            ChartView v = view();
            int[] cell = v.cellAt(e.getX(), e.getY());
            if (cell == null) return null;
            int r = cell[0] % chart.height(), col = cell[1] % chart.width();
            Stitch s = chart.cells[r][col];
            boolean rs = chart.inTheRound || cell[0] % 2 == 0;
            String side = chart.inTheRound ? "" : rs ? " (RS)" : " (WS)";
            String what = s.label + (chart.isColourwork() ? ", yarn " + chart.yarn(r, col).letter() : "");
            return chart.rowWord() + " " + (cell[0] + 1) + side + ", stitch " + (v.cols() - cell[1]) + ": " + what;
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (chart == null) return;
            Graphics2D g = (Graphics2D) g0.create();
            view().paint(g, highlight, highlightStitch);
            g.dispose();
        }

        void refresh() { revalidate(); repaint(); }
    }

    static JEditorPane htmlPane() {
        JEditorPane p = new JEditorPane("text/html", "");
        p.setEditable(false);
        p.setOpaque(true);
        p.setBackground(Renderer.PAPER);
        p.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        return p;
    }

    static void setHtml(JEditorPane pane, String html) {
        pane.setText(html);
        pane.setCaretPosition(0);
    }

    /** Written pattern: description, key with real symbols, row instructions and notes. */
    static final class InstructionsPanel extends JPanel {
        final JEditorPane pane = htmlPane();
        InstructionsPanel() {
            super(new BorderLayout());
            add(new JScrollPane(pane));
        }
        boolean pictures = true;
        void setChart(Chart c) { setHtml(pane, Html.pattern(c, s -> Html.iconFile(s, pictures), null, false)); }
    }

    /** Pattern checks with a friendly explanation. */
    static final class ChecksPanel extends JPanel {
        final JEditorPane pane = htmlPane();
        int count;
        ChecksPanel() {
            super(new BorderLayout());
            add(new JScrollPane(pane));
        }
        void setChart(Chart c) {
            List<String> w = Checks.run(c);
            count = w.size();
            StringBuilder h = new StringBuilder("<html><head><style>" + Html.SWING_CSS + "</style></head><body>");
            if (w.isEmpty()) {
                h.append("<h1>All good ✓</h1><p>Every ").append(c.rowWord().toLowerCase())
                 .append(" uses exactly the stitches left by the one before, cables have the right number of squares")
                 .append(c.isColourwork() ? ", and no float is longer than " + Checks.LONG_FLOAT + " stitches." : ".")
                 .append("</p>");
            } else {
                h.append("<h1>").append(w.size()).append(w.size() == 1 ? " thing" : " things").append(" to look at</h1><ul>");
                for (String s : w) h.append("<li class='warn'>").append(Html.esc(s)).append("</li>");
                h.append("</ul>");
            }
            h.append("<h2>What is checked</h2><p class='muted'>")
             .append("<b>Stitch counts.</b> Each square is one stitch left on the needle. A decrease uses 2 or 3 stitches but ")
             .append("fills one square; a yarn over or a make-one adds a stitch. If a row needs more stitches than the row before ")
             .append("left behind, the chart has a mistake.</p><p class='muted'>")
             .append("<b>Right-side stitches.</b> Cables, double decreases and make-ones are normally worked on right-side rows.</p>")
             .append("<p class='muted'><b>Floats.</b> In colourwork, a strand carried behind more than ")
             .append(Checks.LONG_FLOAT).append(" stitches can snag, so it should be caught halfway.</p>");
            setHtml(pane, h.append("</body></html>").toString());
        }
    }

    /**
     * Keeps the screensaver and display sleep away while the row tracker is in use.
     * Java has no built-in way to do this, so each system gets its own method:
     *   macOS   - the built-in caffeinate command holds the display awake for as long
     *             as this program runs, and is told the user is active every 30 seconds.
     *   Linux   - "xdg-screensaver reset" every 30 seconds, or a mouse nudge if it's missing.
     *   Windows - the mouse pointer is nudged 1 pixel and back every 30 seconds,
     *             which resets the idle timer without visibly moving it.
     * It gives up after a stretch with no row changes, so a forgotten tracker
     * doesn't keep the screen on all night.
     */
    static final class KeepAwake {
        static final int NUDGE_SECONDS = 30;
        static final int IDLE_LIMIT_MINUTES = 30;

        final String os;
        long idleLimitMs = IDLE_LIMIT_MINUTES * 60_000L;
        private final Runnable onChange;
        private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "keep-screen-awake");
            t.setDaemon(true);
            return t;
        });
        private ScheduledFuture<?> task;
        private Process holder;
        private Robot robot;
        private Boolean hasXdg;
        private volatile long deadline;
        private volatile boolean active, expired;

        KeepAwake(String osName, Runnable onChange) {
            this.os = osName.toLowerCase(Locale.ROOT);
            this.onChange = onChange;
        }

        boolean isMac()     { return os.contains("mac"); }
        boolean isWindows() { return os.contains("win"); }
        boolean isActive()  { return active; }
        boolean isExpired() { return expired; }

        /** caffeinate is part of every Mac since 2012; elsewhere a mouse nudge needs a screen. */
        boolean supported() { return isMac() || !GraphicsEnvironment.isHeadless(); }

        /** Starts keeping the screen awake, or pushes back the time limit if it already is. */
        synchronized void start() {
            deadline = System.currentTimeMillis() + idleLimitMs;
            expired = false;
            if (active || !supported()) return;
            active = true;
            if (isMac()) holder = launch(caffeinate(), "-d", "-i", "-w", String.valueOf(ProcessHandle.current().pid()));
            task = ticker.scheduleAtFixedRate(this::tick, 0, NUDGE_SECONDS, TimeUnit.SECONDS);
            onChange.run();
        }

        synchronized void stop() {
            if (!active) return;
            active = false;
            if (task != null) task.cancel(false);
            if (holder != null) holder.destroy();
            task = null;
            holder = null;
            onChange.run();
        }

        private void tick() {
            if (System.currentTimeMillis() > deadline) {
                expired = true;
                stop();
                return;
            }
            try {
                if (isMac()) launch(caffeinate(), "-u", "-t", "2");
                else if (!isWindows() && xdgAvailable()) launch("xdg-screensaver", "reset");
                else nudgeMouse();
            } catch (RuntimeException ignored) {
                // Best effort: if this fails, the screensaver just behaves as normal.
            }
        }

        static String caffeinate() {
            return Files.isExecutable(Path.of("/usr/bin/caffeinate")) ? "/usr/bin/caffeinate" : "caffeinate";
        }

        private boolean xdgAvailable() {
            if (hasXdg == null) {
                hasXdg = false;
                String path = System.getenv("PATH");
                if (path != null)
                    for (String dir : path.split(File.pathSeparator))
                        if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, "xdg-screensaver"))) hasXdg = true;
            }
            return hasXdg;
        }

        private static Process launch(String... command) {
            try {
                return new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (IOException e) {
                return null;
            }
        }

        private void nudgeMouse() {
            try {
                if (robot == null) robot = new Robot();
                PointerInfo p = MouseInfo.getPointerInfo();
                if (p == null) return;
                Point at = p.getLocation();
                robot.mouseMove(at.x + 1, at.y);
                robot.mouseMove(at.x, at.y);
            } catch (AWTException | SecurityException ignored) {
                // No permission to move the pointer: nothing more we can do.
            }
        }
    }

    /** Follow along row by row; your place is remembered for each pattern. */
    static final class TrackerPanel extends JPanel {
        static final Preferences PREFS = prefsNode();
        static final String KEEP_AWAKE_PREF = "~keep-screen-awake";
        final BiConsumer<Integer, int[]> highlighter;   // chart row, and {column, squares} of the current stitch
        Chart chart;
        int row = 1;   // the row you are about to work, counted from the cast-on (1-based)
        int step;      // the stitch you are about to work in this row (0-based, within the chart repeat)
        int across = 1; // which repeat across the row you are in (1-based)
        boolean inUse;  // the tracker tab is showing and the window isn't minimised
        /** Rows planned in the Size & yarn tab, and how many repeats fit across the planned width. */
        IntSupplier plannedRows = () -> 0;
        ToIntFunction<Chart> repeatsAcross = c -> 1;

        final JLabel big = new JLabel(), sub = new JLabel(), nextLabel = new JLabel(), awakeLabel = new JLabel(),
                     stitchLabel = new JLabel(" ", SwingConstants.CENTER), acrossLabel = new JLabel(" ", SwingConstants.CENTER);
        final JProgressBar progress = new JProgressBar();
        final JTextArea text = new JTextArea();
        final JCheckBox awakeBox = new JCheckBox("Keep the screen awake while I'm using the tracker",
                PREFS == null || PREFS.getBoolean(KEEP_AWAKE_PREF, true));
        final KeepAwake awake = new KeepAwake(System.getProperty("os.name", ""),
                () -> SwingUtilities.invokeLater(this::updateAwakeLabel));

        static Preferences prefsNode() {
            try { return Preferences.userRoot().node("knitting-pattern-studio/tracker"); }
            catch (SecurityException e) { return null; }
        }

        TrackerPanel(BiConsumer<Integer, int[]> highlighter) {
            super(new BorderLayout());
            this.highlighter = highlighter;
            setBorder(new EmptyBorder(16, 16, 16, 16));
            setBackground(Renderer.PAPER);

            big.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            big.setForeground(Renderer.ACCENT);
            sub.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            sub.setForeground(new Color(0x6E6660));
            text.setFont(new Font(Font.SERIF, Font.PLAIN, 22));
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setEditable(false);
            text.setFocusable(false);   // so the arrow keys move through the stitches
            text.setBackground(Renderer.PAPER);
            text.setBorder(new EmptyBorder(14, 0, 14, 0));
            nextLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            nextLabel.setForeground(new Color(0x6E6660));

            progress.setStringPainted(true);
            progress.setForeground(Renderer.ACCENT);
            progress.setToolTipText("How far you are through the length planned in the Size & yarn tab.");
            JPanel head = new JPanel(new GridLayout(0, 1, 0, 4));
            head.setOpaque(false);
            head.add(big);
            head.add(sub);
            head.add(progress);

            // Stitch by stitch
            JButton prevSt = new JButton("◀  Back a stitch"), nextSt = new JButton("Next stitch  ▶");
            prevSt.addActionListener(e -> moveStitch(-1));
            nextSt.addActionListener(e -> moveStitch(1));
            stitchLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            stitchLabel.setForeground(Renderer.INK);
            acrossLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            acrossLabel.setForeground(new Color(0x6E6660));
            JPanel stitchText = new JPanel(new GridLayout(0, 1));
            stitchText.setOpaque(false);
            stitchText.add(stitchLabel);
            stitchText.add(acrossLabel);
            JPanel stitchBar = new JPanel(new BorderLayout(8, 0));
            stitchBar.setOpaque(false);
            stitchBar.setBorder(new EmptyBorder(8, 0, 0, 0));
            stitchBar.add(prevSt, BorderLayout.WEST);
            stitchBar.add(stitchText, BorderLayout.CENTER);
            stitchBar.add(nextSt, BorderLayout.EAST);

            JButton prev = new JButton("◀  Back a row"), next = new JButton("Done — next row  ▶"),
                    reset = new JButton("Start again"), go = new JButton("Go to row…");
            next.setFont(next.getFont().deriveFont(Font.BOLD, 15f));
            prev.addActionListener(e -> move(-1));
            next.addActionListener(e -> move(1));
            reset.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(this, "Go back to row 1?", "Start again", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) setRow(1);
            });
            go.addActionListener(e -> {
                String s = JOptionPane.showInputDialog(this, "Which " + chart.rowWord().toLowerCase() + " are you on? (counting from the cast-on)", row);
                try { if (s != null) setRow(Math.max(1, Integer.parseInt(s.strip()))); }
                catch (NumberFormatException ex) { Toolkit.getDefaultToolkit().beep(); }
            });

            JPanel buttons = new JPanel(new GridLayout(2, 2, 8, 8));
            buttons.setOpaque(false);
            buttons.add(prev); buttons.add(next); buttons.add(go); buttons.add(reset);
            // Buttons don't take the keyboard focus, so Space and the arrow keys always reach the tracker.
            for (JButton b : new JButton[] { prev, next, go, reset, prevSt, nextSt }) b.setFocusable(false);

            JLabel tip = new JLabel("<html>Your place is saved automatically for each pattern. "
                    + "Keep this tab open and the chart highlights the row and stitch you're on.<br>"
                    + "<b>Keys:</b> → or Space next stitch, ← back a stitch, ↑ next row, ↓ back a row.</html>");
            tip.setForeground(new Color(0x6E6660));

            awakeBox.setOpaque(false);
            awakeBox.setFocusable(false);
            awakeBox.setToolTipText("Stops the screensaver and screen sleep while this tab is open. "
                    + "The screen may sleep again " + KeepAwake.IDLE_LIMIT_MINUTES + " minutes after you last move on a row or stitch.");
            awakeBox.addActionListener(e -> {
                if (PREFS != null) PREFS.putBoolean(KEEP_AWAKE_PREF, awakeBox.isSelected());
                applyAwake();
            });
            awakeLabel.setForeground(new Color(0x6E6660));
            awakeLabel.setFont(awakeLabel.getFont().deriveFont(11.5f));

            JPanel foot = new JPanel(new GridLayout(0, 1, 0, 2));
            foot.setOpaque(false);
            foot.add(awakeBox);
            foot.add(awakeLabel);
            foot.add(tip);

            JPanel upper = new JPanel(new BorderLayout(0, 12));
            upper.setOpaque(false);
            upper.add(stitchBar, BorderLayout.NORTH);
            upper.add(nextLabel, BorderLayout.SOUTH);
            JPanel south = new JPanel(new BorderLayout(0, 12));
            south.setOpaque(false);
            south.add(upper, BorderLayout.NORTH);
            south.add(buttons, BorderLayout.CENTER);
            south.add(foot, BorderLayout.SOUTH);
            updateAwakeLabel();

            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); }
            });
            InputMap im = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
            ActionMap am = getActionMap();
            bind(im, am, "nextStitch", () -> moveStitch(1), KeyEvent.VK_RIGHT, KeyEvent.VK_SPACE);
            bind(im, am, "prevStitch", () -> moveStitch(-1), KeyEvent.VK_LEFT, KeyEvent.VK_BACK_SPACE);
            bind(im, am, "nextRow", () -> move(1), KeyEvent.VK_UP);
            bind(im, am, "prevRow", () -> move(-1), KeyEvent.VK_DOWN);

            add(head, BorderLayout.NORTH);
            JScrollPane sp = new JScrollPane(text);
            sp.setBorder(new MatteBorder(1, 0, 1, 0, new Color(0xE6DED4)));
            add(sp, BorderLayout.CENTER);
            add(south, BorderLayout.SOUTH);
        }

        static void bind(InputMap im, ActionMap am, String name, Runnable action, int... keys) {
            for (int k : keys) im.put(KeyStroke.getKeyStroke(k, 0), name);
            am.put(name, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { action.run(); }
            });
        }

        String key() {
            String k = chart.name.length() > 60 ? chart.name.substring(0, 60) : chart.name;
            return k + "#" + Integer.toHexString(chart.name.hashCode());
        }

        void setChart(Chart c) {
            chart = c;
            row = PREFS == null ? 1 : Math.max(1, PREFS.getInt(key(), 1));
            step = 0;
            across = 1;
            refresh();
            applyAwake();
        }

        void move(int d) { setRow(Math.max(1, row + d)); }

        void setRow(int r) {
            row = r;
            step = 0;
            across = 1;
            if (PREFS != null) PREFS.putInt(key(), row);
            refresh();
            applyAwake();   // every row change restarts the time limit
        }

        /** Told by the window whenever the tracker becomes visible or hidden. */
        void setInUse(boolean on) {
            inUse = on;
            applyAwake();
        }

        void applyAwake() {
            if (inUse && awakeBox.isSelected()) awake.start();
            else awake.stop();
            updateAwakeLabel();
        }

        void updateAwakeLabel() {
            if (!awake.supported()) {
                awakeBox.setEnabled(false);
                awakeLabel.setText("Keeping the screen awake isn't available on this computer.");
            } else if (!awakeBox.isSelected()) {
                awakeLabel.setText("Your screensaver and screen sleep work as normal.");
            } else if (awake.isActive()) {
                awakeLabel.setText("Screen kept awake until " + KeepAwake.IDLE_LIMIT_MINUTES
                        + " minutes after you last move on a row or stitch.");
            } else if (awake.isExpired()) {
                awakeLabel.setText("No movement for " + KeepAwake.IDLE_LIMIT_MINUTES
                        + " minutes, so the screen may sleep. Move on a row or stitch to wake it.");
            } else {
                awakeLabel.setText("The screen is kept awake while this tab is open.");
            }
        }

        int chartRow() { return chart == null ? -1 : (row - 1) % chart.height(); }

        int acrossTotal() { return chart == null || chart.panel ? 1 : Math.max(1, repeatsAcross.applyAsInt(chart)); }

        /**
         * Moves one stitch forwards or backwards. After the last stitch of the repeat it starts the
         * next repeat across; after the last repeat across it moves on to the next row.
         */
        void moveStitch(int d) {
            if (chart == null) return;
            List<Instructions.Step> steps = Instructions.steps(chart, chartRow());
            if (steps.isEmpty()) { move(d); return; }
            step += d;
            if (step >= steps.size()) {
                step = 0;
                if (++across > acrossTotal()) { move(1); return; }
            } else if (step < 0) {
                if (--across < 1) {
                    if (row == 1) { step = 0; across = 1; refresh(); return; }
                    setRow(row - 1);
                    across = acrossTotal();
                    step = Math.max(0, Instructions.steps(chart, chartRow()).size() - 1);
                } else step = steps.size() - 1;
            }
            refresh();
            applyAwake();   // stepping through stitches also counts as using the tracker
        }

        /** The stitch you're on, as {leftmost column, squares covered}, or null. */
        int[] currentStitch() {
            if (chart == null) return null;
            List<Instructions.Step> steps = Instructions.steps(chart, chartRow());
            if (steps.isEmpty()) return null;
            Instructions.Step st = steps.get(Math.min(step, steps.size() - 1));
            return new int[] { st.col(), st.span() };
        }

        void refresh() {
            if (chart == null) return;
            int h = chart.height(), r = chartRow(), repeat = (row - 1) / h + 1;
            int planned = Math.max(0, plannedRows.getAsInt());
            boolean rs = chart.inTheRound || (row - 1) % 2 == 0;
            String side = chart.inTheRound ? "" : rs ? "  ·  right side" : "  ·  wrong side";
            String rw = chart.rowWord().toLowerCase(), rws = chart.rowsWord();
            big.setText(chart.rowWord() + " " + row + side);
            int plannedRepeats = (planned + h - 1) / h;
            sub.setText("Chart " + rw + " " + (r + 1) + " of " + h + "  ·  repeat " + repeat
                    + (planned > 0 && repeat <= plannedRepeats ? " of " + plannedRepeats : ""));

            progress.setVisible(planned > 0);
            if (planned > 0) {
                progress.setMaximum(planned);
                progress.setValue(Math.min(planned, row - 1));
                int left = planned - row;
                progress.setString(row > planned
                        ? "All " + planned + " " + rws + " planned in Size & yarn are done"
                        : left == 0 ? "Last " + rw + " of the " + planned + " planned"
                        : left + " " + (left == 1 ? rw : rws) + " to go after this one (" + planned + " planned)");
            }

            text.setText(Instructions.body(chart, r));
            text.setCaretPosition(0);
            int nr = row % h;
            nextLabel.setText("<html><b>Up next:</b> " + Html.esc(Instructions.body(chart, nr)) + "</html>");

            List<Instructions.Step> steps = Instructions.steps(chart, r);
            int total = acrossTotal();
            if (across > total) across = total;
            if (steps.isEmpty()) {
                stitchLabel.setText("No stitches in this " + rw);
                acrossLabel.setText(" ");
            } else {
                step = Math.min(step, steps.size() - 1);
                stitchLabel.setText("Stitch " + (step + 1) + " of " + steps.size() + ":  " + steps.get(step).text());
                acrossLabel.setText(chart.panel ? "in the panel"
                        : total > 1 ? "repeat " + across + " of " + total + " across the " + rw
                        : "in the repeat");
            }
            highlighter.accept(r, currentStitch());
        }
    }

    /** Gauge, cast-on, rows and yarn estimate. */
    static final class CalculatorPanel extends JPanel {
        record Weight(String name, double sts, double rows, int perBall) {
            @Override public String toString() { return name; }
        }
        static final Weight[] WEIGHTS = {
            new Weight("Lace", 32, 44, 400), new Weight("Fingering / 4-ply", 28, 36, 200),
            new Weight("Sport / 5-ply", 24, 32, 150), new Weight("DK / 8-ply", 22, 30, 115),
            new Weight("Worsted / 10-ply", 20, 26, 95), new Weight("Aran", 18, 24, 85),
            new Weight("Chunky / bulky", 14, 19, 60), new Weight("Super chunky", 10, 14, 40),
        };

        final JComboBox<Weight> weight = new JComboBox<>(WEIGHTS);
        final JComboBox<String> units = new JComboBox<>(new String[] { "Centimetres", "Inches" });
        final JSpinner gaugeSts = dbl(22, 2, 80, 0.5), gaugeRows = dbl(30, 2, 100, 0.5),
                       width = dbl(50, 1, 1000, 1), length = dbl(60, 1, 2000, 1),
                       edges = new JSpinner(new SpinnerNumberModel(2, 0, 40, 1)),
                       perBall = new JSpinner(new SpinnerNumberModel(115, 5, 5000, 5));
        final JLabel gaugeLbl1 = new JLabel(), gaugeLbl2 = new JLabel(), widthLbl = new JLabel(),
                     lengthLbl = new JLabel(), ballLbl = new JLabel();
        final JEditorPane out = htmlPane();
        Chart chart;
        boolean inches, updating;
        Runnable onChange = () -> { };

        double rowsPerUnit() { return val(gaugeRows) / (inches ? 4.0 : 10.0); }
        double stsPerUnit()  { return val(gaugeSts) / (inches ? 4.0 : 10.0); }

        /** Rows (or rounds) needed for the length wanted. */
        int plannedRows() { return Math.max(1, (int) Math.round(val(length) * rowsPerUnit())); }

        /** Repeats of the chart across the cast-on for the width wanted. */
        int repeatsAcross(Chart c) {
            if (c == null || c.panel) return 1;
            int e = (int) val(edges);
            return Math.max(1, (Estimate.castOn(c, val(width), stsPerUnit(), e) - e) / c.width());
        }

        static JSpinner dbl(double v, double min, double max, double step) {
            JSpinner s = new JSpinner(new SpinnerNumberModel(v, min, max, step));
            s.setEditor(new JSpinner.NumberEditor(s, "0.#"));
            return s;
        }
        static double val(JSpinner s) { return ((Number) s.getValue()).doubleValue(); }

        CalculatorPanel() {
            super(new BorderLayout());
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(new EmptyBorder(12, 12, 6, 12));
            GridBagConstraints l = new GridBagConstraints(), f = new GridBagConstraints();
            l.gridx = 0; l.anchor = GridBagConstraints.WEST; l.insets = new Insets(3, 0, 3, 10);
            f.gridx = 1; f.fill = GridBagConstraints.HORIZONTAL; f.weightx = 1; f.insets = new Insets(3, 0, 3, 0);
            Object[][] rows = {
                { new JLabel("Units"), units }, { new JLabel("Yarn weight"), weight },
                { gaugeLbl1, gaugeSts }, { gaugeLbl2, gaugeRows },
                { widthLbl, width }, { lengthLbl, length },
                { new JLabel("Edge stitches"), edges }, { ballLbl, perBall },
            };
            for (int i = 0; i < rows.length; i++) {
                l.gridy = f.gridy = i;
                form.add((Component) rows[i][0], l);
                form.add((Component) rows[i][1], f);
            }
            weight.setSelectedIndex(3);
            weight.setToolTipText("Picks typical values. Always knit a swatch and use your own gauge.");
            edges.setToolTipText("Extra stitches at each side for selvedges or a border (in total, not per side).");
            add(form, BorderLayout.NORTH);
            add(new JScrollPane(out), BorderLayout.CENTER);
            setLabels();

            weight.addActionListener(e -> {
                Weight w = (Weight) weight.getSelectedItem();
                updating = true;
                gaugeSts.setValue(w.sts()); gaugeRows.setValue(w.rows());
                perBall.setValue(inches ? (int) Math.round(w.perBall() * 1.0936 / 5) * 5 : w.perBall());
                updating = false;
                recompute();
            });
            units.addActionListener(e -> {
                boolean nowInches = units.getSelectedIndex() == 1;
                if (nowInches == inches) return;
                double k = nowInches ? 1 / 2.54 : 2.54, yk = nowInches ? 1.0936 : 1 / 1.0936;
                updating = true;
                width.setValue(Math.max(1, Math.round(val(width) * k * 2) / 2.0));
                length.setValue(Math.max(1, Math.round(val(length) * k * 2) / 2.0));
                perBall.setValue((int) Math.max(5, Math.round(val(perBall) * yk)));
                inches = nowInches;
                updating = false;
                setLabels();
                recompute();
            });
            for (JSpinner s : new JSpinner[] { gaugeSts, gaugeRows, width, length, edges, perBall })
                s.addChangeListener(e -> recompute());
        }

        void setLabels() {
            String g = inches ? "4 in" : "10 cm", u = inches ? "in" : "cm";
            gaugeLbl1.setText("Stitches per " + g);
            gaugeLbl2.setText("Rows per " + g);
            widthLbl.setText("Width wanted (" + u + ")");
            lengthLbl.setText("Length wanted (" + u + ")");
            ballLbl.setText((inches ? "Yards" : "Metres") + " per ball");
        }

        void setChart(Chart c) {
            chart = c;
            updating = true;
            edges.setValue(c.inTheRound || c.panel ? 0 : 2);
            updating = false;
            edges.setEnabled(!c.panel);
            width.setEnabled(!c.panel);
            recompute();
        }

        void recompute() {
            if (chart == null || updating) return;
            double stsPerUnit = stsPerUnit(), rowsPerUnit = rowsPerUnit();
            String u = inches ? "in" : "cm", len = inches ? "yd" : "m", rw = chart.rowsWord();
            int w = chart.width(), h = chart.height(), e = (int) val(edges);
            StringBuilder s = new StringBuilder("<html><head><style>" + Html.SWING_CSS + "</style></head><body>");

            int stitches = Estimate.castOn(chart, val(width), stsPerUnit, e);
            s.append("<h2>Width</h2>");
            if (chart.panel) {
                s.append("<p>The ").append(w).append("-stitch panel will be about <b>").append(fmt(w / stsPerUnit)).append(' ').append(u)
                 .append("</b> wide. Add it to your other stitches, with a marker each side.</p>");
                if (chart.hasCables())
                    s.append("<p class='muted'>Cables draw the fabric in, so the panel may come out a little narrower than this.</p>");
            } else {
                int reps = (stitches - e) / w;
                s.append("<p>Cast on <b>").append(stitches).append(" stitches</b> (").append(reps)
                 .append(reps == 1 ? " repeat" : " repeats").append(" of ").append(w)
                 .append(e > 0 ? ", plus " + e + " edge stitches" : "").append(").</p>");
                s.append("<p>Your piece will be about <b>").append(fmt(stitches / stsPerUnit)).append(' ').append(u)
                 .append("</b> wide (you asked for ").append(fmt(val(width))).append(' ').append(u).append(").</p>");
            }

            int rows = plannedRows();
            int full = rows / h, extra = rows % h;
            s.append("<h2>Length</h2><p>Work about <b>").append(rows).append(' ').append(rw).append("</b>");
            if (extra == 0) s.append(", which is exactly ").append(full).append(full == 1 ? " repeat" : " repeats").append(" of the chart.</p>");
            else {
                s.append(": ").append(full).append(full == 1 ? " full repeat" : " full repeats").append(" of the chart, then ")
                 .append(rw).append(" 1–").append(extra).append(" once more.</p>");
                int down = Math.max(h, full * h), upTo = (full + 1) * h;
                s.append("<p class='muted'>To finish at the end of a repeat, stop after ").append(down).append(" or ")
                 .append(upTo).append(' ').append(rw).append(" (about ").append(fmt(down / rowsPerUnit)).append(" or ")
                 .append(fmt(upTo / rowsPerUnit)).append(' ').append(u).append(").</p>");
            }

            double stitchWidthM = (1.0 / stsPerUnit) * (inches ? 0.0254 : 0.01);
            double metres = Estimate.metres(chart, stitches, rows, stitchWidthM);
            double total = inches ? metres * 1.0936 : metres, ball = val(perBall);
            s.append("<h2>Yarn</h2><p>Roughly <b>").append(Math.round(total)).append(' ').append(len).append("</b>")
             .append(chart.panel ? " for the panel alone" : "").append(" — about <b>").append(balls(total, ball))
             .append("</b> of ").append((int) ball).append(' ').append(len).append(".</p>");
            if (chart.isColourwork()) {
                double[] share = Estimate.yarnShares(chart);
                s.append("<table>");
                for (int i = 0; i < share.length; i++) {
                    double part = total * share[i];
                    s.append("<tr><td bgcolor='").append(Html.hex(chart.yarns.get(i).color())).append("' width='18'>&nbsp;</td><td>Yarn ")
                     .append(Html.esc(chart.yarns.get(i).letter())).append(" (").append(Html.esc(chart.yarns.get(i).name()))
                     .append(")</td><td>about ").append(Math.round(part)).append(' ').append(len).append(", ")
                     .append(balls(part, ball)).append("</td></tr>");
                }
                s.append("</table>");
            }
            s.append("<p class='muted'>This is a rough estimate from typical yarn use, with 10% extra allowed. ")
             .append("Cables and colourwork use more yarn and are allowed for. Always knit a gauge swatch first, ")
             .append("and buy an extra ball from the same dye lot.</p>");
            setHtml(out, s.append("</body></html>").toString());
            onChange.run();
        }

        static String fmt(double d) { return d >= 100 ? String.valueOf(Math.round(d)) : String.format("%.1f", d); }
        static String balls(double length, double perBall) {
            int n = Math.max(1, (int) Math.ceil(length / perBall));
            return n + (n == 1 ? " ball" : " balls");
        }
    }

    /** The main window. */
    static final class StudioFrame extends JFrame {
        final DefaultListModel<Chart> model = new DefaultListModel<>();
        final JList<Chart> list = new JList<>(model);
        final ChartPanel chartPanel = new ChartPanel();
        final InstructionsPanel instructions = new InstructionsPanel();
        final ChecksPanel checks = new ChecksPanel();
        final CalculatorPanel calculator = new CalculatorPanel();
        final TrackerPanel tracker;
        final JTabbedPane tabs = new JTabbedPane();
        final JSpinner across = new JSpinner(new SpinnerNumberModel(2, 1, 12, 1)), up = new JSpinner(new SpinnerNumberModel(1, 1, 12, 1));
        final JSlider zoom = new JSlider(10, 48, 24);
        final JToggleButton editButton = new JToggleButton("✎ Edit chart");
        static final Preferences VIEW_PREFS = viewPrefs();
        static final String PICTURES_PREF = "stitchPictures";
        final JCheckBox pictureBox = new JCheckBox("Stitch pictures",
                VIEW_PREFS == null || VIEW_PREFS.getBoolean(PICTURES_PREF, true));
        final JCheckBoxMenuItem editItem = new JCheckBoxMenuItem("Edit chart");
        final EditBar editBar = new EditBar();
        final JLabel status = new JLabel(" ");
        final Deque<Chart> undo = new ArrayDeque<>();
        JScrollPane chartScroll;
        static final int TRACKER_TAB = 1, CHECKS_TAB = 3;
        Chart current;
        File lastDir;
        boolean keepEditing;

        StudioFrame(List<Chart> charts, Chart initial) {
            super(APP_NAME);
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e)     { quit(); }
                @Override public void windowIconified(WindowEvent e)   { tracker.setInUse(false); }
                @Override public void windowDeiconified(WindowEvent e) { tracker.setInUse(trackerInUse()); }
            });
            charts.forEach(model::addElement);

            tracker = new TrackerPanel((r, st) -> {
                boolean on = tabs.getSelectedIndex() == TRACKER_TAB;
                chartPanel.highlight = on ? r : -1;
                chartPanel.highlightStitch = on ? st : null;
                chartPanel.repaint();
            });
            tracker.plannedRows = calculator::plannedRows;
            tracker.repeatsAcross = calculator::repeatsAcross;
            calculator.onChange = tracker::refresh;

            // Pattern list
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                    super.getListCellRendererComponent(l, v, i, sel, foc);
                    Chart c = (Chart) v;
                    setText("<html><body style='width:150px'><b>" + Html.esc(c.name) + (c.dirty ? " •" : "") + "</b><br><font color='"
                            + (sel ? "#F2ECE4" : "#7B726B") + "'>" + Html.esc(c.summary()) + "</font></html>");
                    setIcon(Renderer.thumbnail(c, 46));
                    setIconTextGap(10);
                    setBackground(sel ? Renderer.ACCENT : Color.WHITE);
                    setForeground(sel ? Color.WHITE : Renderer.INK);
                    setOpaque(true);
                    setBorder(new EmptyBorder(6, 10, 6, 10));
                    return this;
                }
            });
            list.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && list.getSelectedValue() != null && list.getSelectedValue() != current) show(list.getSelectedValue());
            });
            JLabel listTitle = new JLabel("Patterns");
            listTitle.setFont(listTitle.getFont().deriveFont(Font.BOLD, 15f));
            listTitle.setBorder(new EmptyBorder(10, 10, 6, 10));
            JButton newB = new JButton("New…"), openB = new JButton("Open…");
            newB.addActionListener(e -> newPattern());
            openB.addActionListener(e -> open());
            JPanel listButtons = new JPanel(new GridLayout(1, 2, 6, 0));
            listButtons.setBorder(new EmptyBorder(6, 8, 8, 8));
            listButtons.add(newB); listButtons.add(openB);
            JPanel left = new JPanel(new BorderLayout());
            left.add(listTitle, BorderLayout.NORTH);
            left.add(new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
            left.add(listButtons, BorderLayout.SOUTH);
            left.setPreferredSize(new Dimension(265, 400));

            // Chart toolbar
            JToolBar tb = new JToolBar();
            tb.setFloatable(false);
            tb.add(new JLabel(" Repeats across "));
            tb.add(across);
            tb.add(new JLabel("  up "));
            tb.add(up);
            tb.addSeparator();
            tb.add(new JLabel("Zoom "));
            zoom.setMaximumSize(new Dimension(140, 30));
            tb.add(zoom);
            tb.addSeparator();
            pictureBox.setToolTipText("<html>Show a picture of each stitch as it looks in the knitted fabric,<br>"
                    + "instead of the standard chart symbols. Printing and export always use the symbols.</html>");
            pictureBox.setFocusable(false);
            pictureBox.addActionListener(e -> setPictures(pictureBox.isSelected()));
            tb.add(pictureBox);
            tb.addSeparator();
            tb.add(editButton);
            across.setMaximumSize(new Dimension(60, 30));
            up.setMaximumSize(new Dimension(60, 30));
            across.addChangeListener(e -> { chartPanel.across = (int) across.getValue(); chartPanel.refresh(); });
            up.addChangeListener(e -> { chartPanel.up = (int) up.getValue(); chartPanel.refresh(); });
            zoom.addChangeListener(e -> { chartPanel.cs = zoom.getValue(); chartPanel.refresh(); });
            editButton.addActionListener(e -> setEditing(editButton.isSelected()));
            editBar.setVisible(false);

            JPanel north = new JPanel(new BorderLayout());
            north.add(tb, BorderLayout.NORTH);
            north.add(editBar, BorderLayout.SOUTH);

            chartPanel.painter = this::paintCell;
            JPanel chartHolder = new JPanel(new GridBagLayout());   // keeps the chart centred
            chartHolder.setBackground(Color.WHITE);
            chartHolder.add(chartPanel);
            chartScroll = new JScrollPane(chartHolder);
            chartScroll.getVerticalScrollBar().setUnitIncrement(16);
            chartScroll.getHorizontalScrollBar().setUnitIncrement(16);

            JLabel hint = new JLabel("  Read from the bottom up. Each square shows the stitch from the right side. "
                    + "The red box marks one repeat. Hover over a square for details.");
            hint.setForeground(new Color(0x7B726B));
            hint.setBorder(new EmptyBorder(4, 4, 4, 4));

            JPanel centre = new JPanel(new BorderLayout());
            centre.add(north, BorderLayout.NORTH);
            centre.add(chartScroll, BorderLayout.CENTER);
            centre.add(hint, BorderLayout.SOUTH);

            tabs.addTab("Instructions", instructions);
            tabs.addTab("Row tracker", tracker);
            tabs.addTab("Size & yarn", calculator);
            tabs.addTab("Checks", checks);
            tabs.setPreferredSize(new Dimension(460, 600));
            tabs.addChangeListener(e -> {
                boolean on = tabs.getSelectedComponent() == tracker;
                chartPanel.highlight = on ? tracker.chartRow() : -1;
                chartPanel.highlightStitch = on ? tracker.currentStitch() : null;
                chartPanel.repaint();
                tracker.setInUse(trackerInUse());
                if (on) SwingUtilities.invokeLater(tracker::requestFocusInWindow);
            });

            JSplitPane right = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centre, tabs);
            right.setResizeWeight(1.0);
            right.setBorder(null);
            JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
            main.setBorder(null);
            main.setDividerLocation(265);
            right.setDividerLocation(1440 - 265 - 480);

            status.setBorder(new EmptyBorder(3, 10, 3, 10));
            status.setForeground(new Color(0x6E6660));
            JPanel root = new JPanel(new BorderLayout());
            root.add(main, BorderLayout.CENTER);
            root.add(status, BorderLayout.SOUTH);
            setContentPane(root);
            setJMenuBar(menus());

            setPictures(pictureBox.isSelected());
            setSize(1440, 860);
            setLocationRelativeTo(null);
            list.setSelectedValue(initial != null ? initial : model.get(0), true);
        }

        // ---- menus --------------------------------------------------------

        JMenuBar menus() {
            int mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
            JMenuBar bar = new JMenuBar();
            JMenu file = new JMenu("File");
            file.add(item("New pattern…", KeyStroke.getKeyStroke(KeyEvent.VK_N, mod), this::newPattern));
            file.add(item("Open…", KeyStroke.getKeyStroke(KeyEvent.VK_O, mod), this::open));
            file.add(item("Save as…", KeyStroke.getKeyStroke(KeyEvent.VK_S, mod), this::saveAs));
            file.addSeparator();
            file.add(item("Export as web page (HTML)…", null, this::exportHtml));
            file.add(item("Export chart as picture (PNG)…", null, this::exportPng));
            file.add(item("Export as plain text…", null, this::exportText));
            file.addSeparator();
            file.add(item("Print…", KeyStroke.getKeyStroke(KeyEvent.VK_P, mod), this::print));
            file.addSeparator();
            file.add(item("Quit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, mod), this::quit));

            JMenu edit = new JMenu("Edit");
            editItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, mod));
            editItem.addActionListener(e -> setEditing(editItem.isSelected()));
            edit.add(editItem);
            edit.add(item("Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, mod), this::undo));
            edit.addSeparator();
            edit.add(item("Pattern details…", null, this::details));
            edit.add(item("Resize chart…", null, this::resizeChart));
            edit.addSeparator();
            edit.add(item("Duplicate pattern", null, this::duplicate));
            edit.add(item("Remove pattern from list", null, this::remove));

            JMenu help = new JMenu("Help");
            help.add(item("How to read a chart", null, this::howToRead));
            help.add(item("About", null, () -> JOptionPane.showMessageDialog(this,
                    APP_NAME + " " + VERSION + "\nDesign, view and follow knitting charts.\nMIT licence.", "About",
                    JOptionPane.INFORMATION_MESSAGE)));
            bar.add(file); bar.add(edit); bar.add(help);
            return bar;
        }

        JMenuItem item(String text, KeyStroke key, Runnable action) {
            JMenuItem i = new JMenuItem(text);
            if (key != null) i.setAccelerator(key);
            i.addActionListener(e -> action.run());
            return i;
        }

        void say(String s) { status.setText(s); }

        static Preferences viewPrefs() {
            try { return Preferences.userRoot().node("knitting-pattern-studio/view"); }
            catch (RuntimeException e) { return null; }
        }

        /** Switches the chart and the stitch palette between stitch pictures and chart symbols. */
        void setPictures(boolean on) {
            pictureBox.setSelected(on);
            chartPanel.pictures = on;
            instructions.pictures = on;
            if (current != null) instructions.setChart(current);
            editBar.updateIcons(on);
            chartPanel.repaint();
            if (VIEW_PREFS != null) VIEW_PREFS.putBoolean(PICTURES_PREF, on);
        }

        // ---- showing a pattern ---------------------------------------------

        void show(Chart c) {
            current = c;
            undo.clear();
            if (!keepEditing) setEditing(false);
            across.setEnabled(!c.panel);
            chartPanel.chart = c;
            chartPanel.across = (int) across.getValue();
            tracker.setChart(c);
            calculator.setChart(c);
            editBar.rebuildYarns();
            refreshDerived();
            setTitle(APP_NAME + " — " + c.name);
            say(c.builtIn ? "Built-in pattern. Choose “Edit chart” to make your own copy." : " ");
            if (!keepEditing) SwingUtilities.invokeLater(this::fitZoom);
        }

        /** Picks a zoom level that shows the whole chart without scrolling, where possible. */
        void fitZoom() {
            if (current == null || chartScroll == null) return;
            Dimension avail = chartScroll.getViewport().getExtentSize();
            if (avail.width <= 0) return;
            int cols = current.width() * (current.panel ? 1 : (int) across.getValue()), rows = current.height() * (int) up.getValue();
            int cs = Math.min(28, Math.min((avail.width - 100) / cols, (avail.height - 60) / rows));
            zoom.setValue(Math.max(zoom.getMinimum(), cs));
        }

        /** Re-generates everything that comes from the chart. */
        void refreshDerived() {
            instructions.setChart(current);
            checks.setChart(current);
            tabs.setTitleAt(CHECKS_TAB, checks.count == 0 ? "Checks ✓" : "Checks (" + checks.count + ")");
            calculator.recompute();
            tracker.refresh();
            chartPanel.refresh();
            list.repaint();
        }

        // ---- editing ---------------------------------------------------------

        /** Built-in patterns are never changed; editing one makes a copy. */
        void ensureEditable() {
            if (!current.builtIn) return;
            Chart copy = current.copy();
            copy.builtIn = false;
            copy.name = current.name + " (my copy)";
            model.addElement(copy);
            keepEditing = true;
            list.setSelectedValue(copy, true);
            keepEditing = false;
            say("Built-in patterns stay as they are, so you're now editing your own copy.");
        }

        void setEditing(boolean on) {
            if (on && current != null) ensureEditable();
            editButton.setSelected(on);
            editItem.setSelected(on);
            editBar.setVisible(on);
            chartPanel.editing = on;
            chartPanel.setCursor(on ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
            if (on) say("Editing: pick a stitch or a yarn above, then click or drag on the chart. Ctrl+Z undoes.");
        }

        void snapshot() {
            undo.push(current.copy());
            while (undo.size() > 100) undo.removeLast();
        }

        void changed() {
            current.dirty = true;
            refreshDerived();
        }

        void undo() {
            if (undo.isEmpty()) { Toolkit.getDefaultToolkit().beep(); return; }
            current.restoreFrom(undo.pop());
            editBar.rebuildYarns();
            changed();
        }

        void paintCell(int[] cell, boolean newStroke) {
            int r = cell[0], col = cell[1], w = current.width();
            if (newStroke) snapshot();
            if (editBar.yarnTool >= 0) {
                if (current.cells[r][col].isCable()) {    // colour the whole cable
                    int[] span = cableSpan(r, col);
                    for (int k = span[0]; k < span[1]; k++) current.colours[r][k] = editBar.yarnTool;
                } else current.colours[r][col] = editBar.yarnTool;
            } else {
                Stitch s = editBar.tool;
                if (current.cells[r][col].isCable()) clearCable(r, col);
                if (s.isCable()) {
                    if (col + s.width > w) { say("A " + s.label + " needs " + s.width + " squares — click further left."); return; }
                    for (int k = 0; k < s.width; k++) {
                        if (current.cells[r][col + k].isCable()) clearCable(r, col + k);
                        current.cells[r][col + k] = s;
                    }
                } else current.cells[r][col] = s;
            }
            changed();
        }

        /** The squares [start, end) of the single cable crossing that contains a square. */
        int[] cableSpan(int r, int col) {
            Stitch s = current.cells[r][col];
            int start = col;
            while (start > 0 && current.cells[r][start - 1] == s) start--;
            int unitStart = start + ((col - start) / s.width) * s.width;
            return new int[] { unitStart, Math.min(current.width(), unitStart + s.width) };
        }

        void clearCable(int r, int col) {
            int[] span = cableSpan(r, col);
            for (int k = span[0]; k < span[1]; k++) current.cells[r][k] = Stitch.KNIT;
        }

        /** Stitch and yarn palette shown while editing. */
        final class EditBar extends JPanel {
            Stitch tool = Stitch.KNIT;
            int yarnTool = -1;
            final ButtonGroup group = new ButtonGroup();
            final JPanel yarnBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            final Map<Stitch, JToggleButton> stitchButtons = new EnumMap<>(Stitch.class);

            /** Shows the stitch buttons the same way as the chart: pictures or symbols. */
            void updateIcons(boolean pictures) {
                stitchButtons.forEach((s, b) -> b.setIcon(new ImageIcon(Renderer.icon(s, iconSize(s), 1, pictures))));
            }

            /** Cable buttons are a little shorter, so the whole row of them fits. */
            int iconSize(Stitch s) { return s.isCable() ? 18 : 20; }

            EditBar() {
                super(new GridLayout(3, 1));
                JToolBar stitches = new JToolBar(), cables = new JToolBar(), actions = new JToolBar();
                stitches.setFloatable(false);
                cables.setFloatable(false);
                actions.setFloatable(false);
                stitches.add(new JLabel(" Stitch "));
                cables.add(new JLabel(" Cable "));
                for (Stitch s : Stitch.values()) {
                    JToggleButton b = new JToggleButton(new ImageIcon(Renderer.icon(s, iconSize(s), 1)));
                    stitchButtons.put(s, b);
                    b.setToolTipText("<html><b>" + s.label + "</b><br>" + Html.esc(s.description(false)) + "</html>");
                    b.setFocusable(false);
                    b.addActionListener(e -> { tool = s; yarnTool = -1; });
                    group.add(b);
                    (s.isCable() ? cables : stitches).add(b);
                    if (s == Stitch.KNIT) b.setSelected(true);
                }
                actions.add(new JLabel(" Yarn "));
                yarnBox.setOpaque(false);
                actions.add(yarnBox);
                JButton addYarn = new JButton("+ Yarn"), undoB = new JButton("↶ Undo"),
                        resize = new JButton("Resize…"), details = new JButton("Details…");
                addYarn.setToolTipText("Add another colour (makes this a colourwork chart).");
                addYarn.addActionListener(e -> addYarn());
                undoB.addActionListener(e -> undo());
                resize.addActionListener(e -> resizeChart());
                details.addActionListener(e -> details());
                for (JButton b : new JButton[] { addYarn, undoB, resize, details }) b.setFocusable(false);
                actions.add(addYarn);
                actions.addSeparator();
                actions.add(undoB);
                actions.add(resize);
                actions.add(details);
                add(stitches);
                add(cables);
                add(actions);
            }

            void rebuildYarns() {
                for (Component c : yarnBox.getComponents()) if (c instanceof AbstractButton b) group.remove(b);
                yarnBox.removeAll();
                if (current != null) {
                    for (int i = 0; i < current.yarns.size(); i++) {
                        int idx = i;
                        Yarn y = current.yarns.get(i);
                        JToggleButton b = new JToggleButton(y.letter(), Renderer.swatch(y.color(), 16));
                        b.setToolTipText("<html>Paint with yarn " + y.letter() + " (" + Html.esc(y.name())
                                + ")<br>Right-click to rename or change the colour.</html>");
                        b.setFocusable(false);
                        b.addActionListener(e -> yarnTool = idx);
                        b.addMouseListener(new MouseAdapter() {
                            @Override public void mousePressed(MouseEvent e)  { if (e.isPopupTrigger()) editYarn(idx); }
                            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) editYarn(idx); }
                        });
                        group.add(b);
                        yarnBox.add(b);
                        if (idx == yarnTool) b.setSelected(true);
                    }
                }
                if (current != null && yarnTool >= current.yarns.size()) yarnTool = -1;
                yarnBox.revalidate();
                yarnBox.repaint();
            }
        }

        void addYarn() {
            ensureEditable();
            if (current.yarns.size() >= DEFAULT_YARNS.length) { say("Up to " + DEFAULT_YARNS.length + " yarns are supported."); return; }
            snapshot();
            Set<String> used = new HashSet<>();
            for (Yarn y : current.yarns) used.add(y.letter());
            for (Yarn y : DEFAULT_YARNS)
                if (!used.contains(y.letter())) { current.yarns.add(y); break; }
            editBar.rebuildYarns();
            changed();
            say("Added yarn " + current.yarns.get(current.yarns.size() - 1).letter() + ". Right-click a yarn button to change its colour.");
        }

        void editYarn(int idx) {
            ensureEditable();
            Yarn y = current.yarns.get(idx);
            JTextField name = new JTextField(y.name(), 14);
            JColorChooser chooser = new JColorChooser(y.color());
            JPanel p = new JPanel(new BorderLayout(0, 8));
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
            top.add(new JLabel("Name for yarn " + y.letter() + ":"));
            top.add(name);
            p.add(top, BorderLayout.NORTH);
            p.add(chooser, BorderLayout.CENTER);
            if (JOptionPane.showConfirmDialog(this, p, "Yarn " + y.letter(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            snapshot();
            current.yarns.set(idx, new Yarn(y.letter(), name.getText().isBlank() ? y.name() : name.getText().strip(), chooser.getColor()));
            editBar.rebuildYarns();
            changed();
        }

        // ---- pattern management ---------------------------------------------

        void newPattern() {
            JTextField name = new JTextField("My new pattern", 18);
            JSpinner w = new JSpinner(new SpinnerNumberModel(12, 1, 80, 1)), h = new JSpinner(new SpinnerNumberModel(8, 1, 120, 1)),
                     yarns = new JSpinner(new SpinnerNumberModel(1, 1, DEFAULT_YARNS.length, 1));
            JCheckBox round = new JCheckBox("Knitted in the round"), panel = new JCheckBox("A single panel (not repeated across)");
            JPanel p = form(new Object[][] {
                { "Name", name }, { "Stitches wide", w }, { "Rows high", h }, { "Number of yarns", yarns }, { "", round }, { "", panel } });
            if (JOptionPane.showConfirmDialog(this, p, "New pattern", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            Chart c = new Chart((int) w.getValue(), (int) h.getValue());
            c.name = name.getText().isBlank() ? "My new pattern" : name.getText().strip();
            c.inTheRound = round.isSelected();
            c.panel = panel.isSelected();
            c.yarns.clear();
            for (int i = 0; i < (int) yarns.getValue(); i++) c.yarns.add(DEFAULT_YARNS[i]);
            c.dirty = true;
            model.addElement(c);
            list.setSelectedValue(c, true);
            setEditing(true);
        }

        static JPanel form(Object[][] rows) {
            JPanel p = new JPanel(new GridBagLayout());
            GridBagConstraints l = new GridBagConstraints(), f = new GridBagConstraints();
            l.gridx = 0; l.anchor = GridBagConstraints.NORTHWEST; l.insets = new Insets(4, 0, 4, 10);
            f.gridx = 1; f.fill = GridBagConstraints.BOTH; f.weightx = 1; f.insets = new Insets(4, 0, 4, 0);
            for (int i = 0; i < rows.length; i++) {
                l.gridy = f.gridy = i;
                p.add(new JLabel((String) rows[i][0]), l);
                p.add((Component) rows[i][1], f);
            }
            return p;
        }

        void details() {
            ensureEditable();
            JTextField name = new JTextField(current.name, 24);
            JTextArea desc = new JTextArea(current.description, 3, 30), notes = new JTextArea(current.notes, 6, 30);
            for (JTextArea a : new JTextArea[] { desc, notes }) { a.setLineWrap(true); a.setWrapStyleWord(true); }
            JCheckBox round = new JCheckBox("Knitted in the round", current.inTheRound),
                      panel = new JCheckBox("A single panel (not repeated across)", current.panel);
            JPanel p = form(new Object[][] {
                { "Name", name }, { "Description", new JScrollPane(desc) }, { "Notes", new JScrollPane(notes) },
                { "", round }, { "", panel } });
            if (JOptionPane.showConfirmDialog(this, p, "Pattern details", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            current.name = name.getText().isBlank() ? current.name : name.getText().strip();
            current.description = desc.getText().strip();
            current.notes = notes.getText().strip();
            current.inTheRound = round.isSelected();
            current.panel = panel.isSelected();
            across.setEnabled(!current.panel);
            calculator.setChart(current);
            tracker.setChart(current);
            setTitle(APP_NAME + " — " + current.name);
            changed();
        }

        void resizeChart() {
            ensureEditable();
            JSpinner w = new JSpinner(new SpinnerNumberModel(current.width(), 1, 80, 1)),
                     h = new JSpinner(new SpinnerNumberModel(current.height(), 1, 120, 1));
            JPanel p = form(new Object[][] { { "Stitches wide", w }, { "Rows high", h } });
            p.add(new JLabel("<html><i>The bottom-left corner stays put; new squares are knit stitches.</i></html>"),
                  new GridBagConstraints(0, 2, 2, 1, 1, 0, GridBagConstraints.WEST, GridBagConstraints.NONE, new Insets(6, 0, 0, 0), 0, 0));
            if (JOptionPane.showConfirmDialog(this, p, "Resize chart", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            snapshot();
            current.resize((int) w.getValue(), (int) h.getValue());
            tracker.refresh();
            changed();
        }

        void duplicate() {
            Chart c = current.copy();
            c.builtIn = false;
            c.dirty = true;
            c.name = current.name + " (copy)";
            model.addElement(c);
            list.setSelectedValue(c, true);
        }

        void remove() {
            if (model.size() <= 1) return;
            if (current.dirty && JOptionPane.showConfirmDialog(this, "“" + current.name + "” has unsaved changes. Remove it anyway?",
                    "Remove pattern", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            int i = model.indexOf(current);
            model.remove(i);
            list.setSelectedIndex(Math.max(0, i - 1));
        }

        // ---- files ------------------------------------------------------------

        JFileChooser chooser(String ext, String what) {
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setFileFilter(new FileNameExtensionFilter(what, ext));
            return fc;
        }

        File chooseSave(String ext, String what) {
            JFileChooser fc = chooser(ext, what);
            fc.setSelectedFile(new File(lastDir, current.name.replaceAll("[^A-Za-z0-9 _()-]", "").strip() + "." + ext));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null;
            File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith("." + ext)) f = new File(f.getParentFile(), f.getName() + "." + ext);
            lastDir = f.getParentFile();
            if (f.exists() && JOptionPane.showConfirmDialog(this, f.getName() + " already exists. Replace it?", "Replace file",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return null;
            return f;
        }

        void open() {
            JFileChooser fc = chooser("knit", "Knitting patterns (*.knit)");
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            lastDir = fc.getSelectedFile().getParentFile();
            try {
                Chart c = ChartIO.load(fc.getSelectedFile().toPath());
                model.addElement(c);
                list.setSelectedValue(c, true);
                say("Opened " + fc.getSelectedFile().getName());
            } catch (IOException | IllegalArgumentException ex) {
                error("Couldn't open that pattern", ex);
            }
        }

        void saveAs() {
            File f = chooseSave("knit", "Knitting patterns (*.knit)");
            if (f == null) return;
            try {
                ChartIO.save(current, f.toPath());
                current.dirty = false;
                list.repaint();
                say("Saved " + f.getName());
            } catch (IOException ex) { error("Couldn't save the pattern", ex); }
        }

        void exportHtml() {
            File f = chooseSave("html", "Web pages (*.html)");
            if (f == null) return;
            try {
                ChartView v = new ChartView(current, chartPanel.across, chartPanel.up, 22);
                String html = Html.pattern(current, s -> Html.dataUri(Renderer.icon(s, 22, 2)), Html.dataUri(v.image(2)), true)
                        .replace("<img class='chart' ", "<img class='chart' width='" + v.size().width + "' ");
                Files.writeString(f.toPath(), html, StandardCharsets.UTF_8);
                say("Exported " + f.getName());
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                        && JOptionPane.showConfirmDialog(this, "Saved. Open it in your web browser now?", "Exported",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                    Desktop.getDesktop().browse(f.toURI());
            } catch (IOException ex) { error("Couldn't export the web page", ex); }
        }

        void exportPng() {
            File f = chooseSave("png", "PNG pictures (*.png)");
            if (f == null) return;
            try {
                ImageIO.write(new ChartView(current, chartPanel.across, chartPanel.up, 24).image(2), "png", f);
                say("Exported " + f.getName());
            } catch (IOException ex) { error("Couldn't export the picture", ex); }
        }

        void exportText() {
            File f = chooseSave("txt", "Text files (*.txt)");
            if (f == null) return;
            try {
                Files.writeString(f.toPath(), new TextOutput(false).render(current), StandardCharsets.UTF_8);
                say("Exported " + f.getName());
            } catch (IOException ex) { error("Couldn't export the text", ex); }
        }

        void print() {
            PrinterJob job = PrinterJob.getPrinterJob();
            job.setJobName(current.name);
            ChartView v = new ChartView(current, chartPanel.across, chartPanel.up, 20);
            job.setPrintable(new PatternPrintable(current, v.image(PatternPrintable.IMAGE_SCALE)));
            if (!job.printDialog()) return;
            try { job.print(); say("Sent to the printer."); }
            catch (PrinterException ex) { error("Couldn't print", ex); }
        }

        void error(String what, Exception ex) {
            JOptionPane.showMessageDialog(this, what + ".\n\n" + ex.getMessage(), "Sorry", JOptionPane.WARNING_MESSAGE);
        }

        boolean trackerInUse() {
            return tabs.getSelectedIndex() == TRACKER_TAB && (getExtendedState() & Frame.ICONIFIED) == 0;
        }

        void quit() {
            List<String> unsaved = new ArrayList<>();
            for (int i = 0; i < model.size(); i++) if (model.get(i).dirty) unsaved.add(model.get(i).name);
            if (!unsaved.isEmpty() && JOptionPane.showConfirmDialog(this,
                    "These patterns have changes that aren't saved:\n  " + String.join("\n  ", unsaved) + "\n\nQuit anyway?",
                    "Unsaved changes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            tracker.awake.stop();
            dispose();
            System.exit(0);
        }

        void howToRead() {
            JEditorPane p = htmlPane();
            setHtml(p, "<html><head><style>" + Html.SWING_CSS + "</style></head><body>"
                + "<h1>How to read a knitting chart</h1>"
                + "<p><b>Start at the bottom.</b> Row 1 is the bottom row of the chart, and you work upwards.</p>"
                + "<p><b>Each square is one stitch</b>, drawn as it looks from the right side (the public side) of your knitting. "
                + "A blank square looks like a smooth knit V; a dot is a purl bump.</p>"
                + "<p><b>Flat knitting:</b> right-side rows (odd numbers, shown on the right of the chart) are read from right to left, "
                + "just as you knit them. Wrong-side rows (even numbers, shown on the left) are read from left to right, "
                + "and you do the opposite of what you see: a blank square is purled, a dot is knitted. The written "
                + "instructions do this for you.</p>"
                + "<p><b>In the round:</b> the right side is always facing you, so every round is read from right to left.</p>"
                + "<p><b>Repeats:</b> the red box marks one repeat. Work it again and again across the row, and repeat the "
                + "rows upwards for the length you need.</p>"
                + "<p><b>Decreases</b> take two or three stitches but fill just one square, because the chart shows the stitches "
                + "left on the needle. Each <b>yarn over</b> adds one, which is why lace rows balance out.</p>"
                + "<p><b>Cables</b> stretch across several squares. The strand on top shows which way the stitches lean: "
                + "leaning left means hold the cable needle at the front, leaning right means hold it at the back.</p>"
                + "</body></html>");
            JScrollPane sp = new JScrollPane(p);
            sp.setPreferredSize(new Dimension(560, 520));
            JOptionPane.showMessageDialog(this, sp, "How to read a chart", JOptionPane.PLAIN_MESSAGE);
        }
    }

    // =======================================================================
    // Start-up
    // =======================================================================

    static void printUsage() {
        System.out.println(String.join("\n",
            APP_NAME + " " + VERSION,
            "",
            "  java KnittingPattern.java                        open the window",
            "  java KnittingPattern.java FILE.knit              open a saved pattern",
            "  java KnittingPattern.java --text                 print all patterns as text",
            "  java KnittingPattern.java --text --pattern NAME  print patterns whose name contains NAME",
            "  java KnittingPattern.java --text FILE.knit       print a saved pattern",
            "  --no-colour                                      plain text without terminal colours"));
    }

    static boolean isTerminal() {
        Console c = System.console();
        if (c == null) return false;
        try { return (Boolean) Console.class.getMethod("isTerminal").invoke(c); }   // Java 22+
        catch (ReflectiveOperationException e) { return true; }
    }

    public static void main(String[] args) {
        boolean text = false, colour = true;
        String patternName = null, file = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--text", "-t" -> text = true;
                case "--no-colour", "--no-color" -> colour = false;
                case "--pattern", "-p" -> { if (i + 1 < args.length) patternName = args[++i]; }
                case "--help", "-h" -> { printUsage(); return; }
                default -> file = args[i];
            }
        }

        List<Chart> charts = BuiltIns.all();
        Chart opened = null;
        if (file != null) {
            try { opened = ChartIO.load(Path.of(file)); charts.add(opened); }
            catch (IOException | IllegalArgumentException e) { System.err.println("Couldn't open " + file + ": " + e.getMessage()); }
        }

        if (!text && GraphicsEnvironment.isHeadless()) {
            System.err.println("No screen found, so the patterns are shown as text. (Use --text to do this on purpose.)");
            text = true;
        }

        if (text) {
            boolean ansi = colour && isTerminal() && System.getenv("NO_COLOR") == null;
            TextOutput out = new TextOutput(ansi);
            List<Chart> chosen = new ArrayList<>();
            if (opened != null && patternName == null) chosen.add(opened);
            else for (Chart c : charts)
                if (patternName == null || c.name.toLowerCase().contains(patternName.toLowerCase())) chosen.add(c);
            if (chosen.isEmpty()) {
                System.err.println("No pattern matches \"" + patternName + "\". Patterns available:");
                for (Chart c : charts) System.err.println("  " + c.name);
                System.exit(1);
            }
            System.out.print(out.banner());
            for (Chart c : chosen) System.out.print(out.render(c));
            System.out.println("\n" + out.a(TextOutput.BOLD + TextOutput.GREEN, "Happy knitting!"));
            return;
        }

        Chart first = opened;
        SwingUtilities.invokeLater(() -> {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win") || os.contains("mac")) UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                else for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels())
                    if (info.getName().equals("Nimbus")) UIManager.setLookAndFeel(info.getClassName());
            } catch (ReflectiveOperationException | UnsupportedLookAndFeelException ignored) { }
            new StudioFrame(charts, first).setVisible(true);
        });
    }
}
