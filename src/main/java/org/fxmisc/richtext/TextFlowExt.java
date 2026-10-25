package org.fxmisc.richtext;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.IndexRange;
import javafx.scene.shape.ArcTo;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.PathElement;
import javafx.scene.text.HitInfo;
import javafx.scene.text.TextFlow;
import org.fxmisc.richtext.model.TwoDimensional.Bias;
import org.fxmisc.richtext.model.TwoLevelNavigator;

import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX 25 compatible replacement for RichTextFX 0.11.5 TextFlowExt.
 * JavaFX 25 introduced public final TextFlow#getUnderlineShape(int, int),
 * which RichTextFX 0.11.5 previously declared as a non-final method.
 */
class TextFlowExt extends TextFlow {

    private TextFlowLayout layout;

    TextFlowExt() {
        super();
    }

    private TextFlowLayout textLayout() {
        if (layout == null) {
            layout = new TextFlowLayout(this);
        }
        return layout;
    }

    int getLineCount() {
        return textLayout().getLineCount();
    }

    int getLineStartPosition(int charIdx) {
        TwoLevelNavigator navigator = textLayout().getTwoLevelNavigator();
        int lineIdx = navigator.offsetToPosition(charIdx, Bias.Forward).getMajor();
        return navigator.position(lineIdx, 0).toOffset();
    }

    int getLineEndPosition(int charIdx) {
        TwoLevelNavigator navigator = textLayout().getTwoLevelNavigator();
        int lineIdx = navigator.offsetToPosition(charIdx, Bias.Forward).getMajor() + 1;
        int minor = (lineIdx == getLineCount()) ? 0 : -1;
        return navigator.position(lineIdx, minor).toOffset();
    }

    int getLineOfCharacter(int charIdx) {
        TwoLevelNavigator navigator = textLayout().getTwoLevelNavigator();
        return navigator.offsetToPosition(charIdx, Bias.Forward).getMajor();
    }

    @Override
    public PathElement[] getCaretShape(int charIdx, boolean isLeading) {
        return caretShape(charIdx, isLeading);
    }

    PathElement[] getRangeShape(IndexRange range) {
        return getRangeShape(range.getStart(), range.getEnd());
    }

    PathElement[] getRangeShape(int from, int to) {
        return rangeShape(from, to);
    }

    PathElement[] getUnderlineShape(IndexRange range) {
        return getUnderlineShape(range.getStart(), range.getEnd(), 0, 0, 0);
    }

    PathElement[] getUnderlineShape(
            int from,
            int to,
            double offset,
            double waveRadius,
            double doubleUnderlineGap
    ) {
        List<PathElement> result = new ArrayList<>();
        PathElement[] shape = rangeShape(from, to);
        boolean doubleUnderline = doubleUnderlineGap > 0;
        List<PathElement> secondUnderline = new ArrayList<>();

        for (int i = 2; i < shape.length; i += 5) {
            LineTo bottomLeft = (LineTo) shape[i + 1];
            LineTo bottomRight = (LineTo) shape[i];
            double y = snapSizeY(bottomRight.getY() + offset - 2.5);
            double xLeft = snapSizeX(bottomLeft.getX());

            if (waveRadius <= 0) {
                result.add(new MoveTo(xLeft, y));
                result.add(new LineTo(snapSizeX(bottomRight.getX()), y));
                if (doubleUnderline) {
                    y += doubleUnderlineGap;
                    secondUnderline.add(new MoveTo(xLeft, y));
                    secondUnderline.add(new LineTo(snapSizeX(bottomRight.getX()), y));
                }
            } else {
                double radiusX = waveRadius > 1 ? waveRadius * 1.25 : waveRadius;
                double xRight = bottomRight.getX();
                result.add(new MoveTo(xLeft, y));
                if (doubleUnderline) {
                    secondUnderline.add(new MoveTo(xLeft, y + doubleUnderlineGap));
                }
                boolean sweep = true;
                while (xLeft < xRight) {
                    xLeft += waveRadius * 2.0;
                    if (xLeft > xRight) {
                        double dx = xRight - (xLeft - waveRadius);
                        double dxSq = dx * dx;
                        double rxSq = radiusX * radiusX;
                        double rySq = waveRadius * waveRadius;
                        double dy = waveRadius * (Math.sqrt(1.0 - dxSq / rxSq) - Math.sqrt(1.0 - rySq / rxSq));
                        if (sweep) {
                            y -= dy;
                        } else {
                            y += dy;
                        }
                        xLeft = xRight;
                    }
                    result.add(new ArcTo(radiusX, waveRadius, 0.0, xLeft, y, false, sweep));
                    if (doubleUnderline) {
                        secondUnderline.add(new ArcTo(
                                radiusX, waveRadius, 0.0, xLeft, y + doubleUnderlineGap, false, sweep
                        ));
                    }
                    sweep = !sweep;
                }
            }
        }
        if (doubleUnderline) {
            result.addAll(secondUnderline);
        }
        return result.toArray(new PathElement[0]);
    }

    CharacterHit hitLine(double x, int lineIndex) {
        return hit(x, textLayout().getLineCenter(lineIndex));
    }

    CharacterHit hit(double x, double y) {
        TextFlowSpan span = textLayout().getLineSpan((float) y);
        Rectangle2D lineBounds = span.getBounds();
        HitInfo hit = hitTest(new Point2D(x, y));
        int charIdx = hit.getCharIndex();
        boolean leading = hit.isLeading();

        if (y >= span.getBounds().getMaxY()) {
            return CharacterHit.insertionAt(charIdx);
        }

        if (!leading && getLineCount() > 1) {
            leading = (getLineOfCharacter(charIdx) + 1 < getLineCount()
                    && charIdx + 1 >= span.getStart() + span.getLength());
        }

        if (x < lineBounds.getMinX() || x > lineBounds.getMaxX()) {
            if (leading) {
                return CharacterHit.insertionAt(charIdx);
            } else {
                return CharacterHit.insertionAt(charIdx + 1);
            }
        } else {
            if (leading) {
                return CharacterHit.leadingHalfOf(charIdx);
            } else {
                return CharacterHit.trailingHalfOf(charIdx);
            }
        }
    }
}
