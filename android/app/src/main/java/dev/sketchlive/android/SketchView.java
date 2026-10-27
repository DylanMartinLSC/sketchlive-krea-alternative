package dev.sketchlive.android;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.io.*;
import java.util.*;

final class SketchView extends View {
  static final int SIZE = 768;
  private final Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
  private final Canvas canvas = new Canvas(bitmap);
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint display = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final Rect bounds = new Rect();
  private final ArrayDeque<Bitmap> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  int color = Color.rgb(220, 110, 40);
  float brushSize = 22;
  boolean eraser;
  Runnable changed = () -> {};
  private float lastX, lastY;
  private boolean drawing;

  SketchView(Context context) {
    super(context);
    canvas.drawColor(Color.WHITE);
    setContentDescription("Drawing canvas. Draw with your finger or stylus.");
    setLayerType(LAYER_TYPE_SOFTWARE, null);
  }

  @Override
  protected void onMeasure(int w, int h) {
    int width = MeasureSpec.getSize(w);
    setMeasuredDimension(width, width);
  }

  @Override
  protected void onDraw(Canvas c) {
    bounds.set(0, 0, getWidth(), getHeight());
    c.drawBitmap(bitmap, null, bounds, display);
  }

  private void checkpoint() {
    undo.addLast(bitmap.copy(Bitmap.Config.ARGB_8888, false));
    while (undo.size() > 12) undo.removeFirst().recycle();
    clearStack(redo);
  }

  private static void clearStack(ArrayDeque<Bitmap> stack) {
    while (!stack.isEmpty()) stack.removeFirst().recycle();
  }

  void undo() {
    if (undo.isEmpty()) return;
    redo.addLast(bitmap.copy(Bitmap.Config.ARGB_8888, false));
    Bitmap b = undo.removeLast();
    canvas.drawBitmap(b, 0, 0, null);
    b.recycle();
    notifyChange();
  }

  void redo() {
    if (redo.isEmpty()) return;
    undo.addLast(bitmap.copy(Bitmap.Config.ARGB_8888, false));
    Bitmap b = redo.removeLast();
    canvas.drawBitmap(b, 0, 0, null);
    b.recycle();
    notifyChange();
  }

  void clear() {
    checkpoint();
    canvas.drawColor(Color.WHITE);
    notifyChange();
  }

  void fill() {
    checkpoint();
    canvas.drawColor(color);
    notifyChange();
  }

  void place(Bitmap b) {
    checkpoint();
    float scale = Math.max((float) SIZE / b.getWidth(), (float) SIZE / b.getHeight());
    float w = b.getWidth() * scale, h = b.getHeight() * scale;
    canvas.drawColor(Color.WHITE);
    canvas.drawBitmap(
        b,
        null,
        new RectF((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2),
        display);
    notifyChange();
  }

  Bitmap snapshot() {
    return bitmap.copy(Bitmap.Config.ARGB_8888, false);
  }

  void save(File file) throws IOException {
    try (FileOutputStream out = new FileOutputStream(file)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
  }

  void restore(File file) {
    Bitmap b = BitmapFactory.decodeFile(file.toString());
    if (b != null) {
      canvas.drawBitmap(b, null, new Rect(0, 0, SIZE, SIZE), display);
      b.recycle();
      invalidate();
    }
  }

  private void notifyChange() {
    invalidate();
    changed.run();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    float x = event.getX() * SIZE / getWidth(), y = event.getY() * SIZE / getHeight();
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        getParent().requestDisallowInterceptTouchEvent(true);
        checkpoint();
        drawing = true;
        lastX = x;
        lastY = y;
        drawLine(x, y, event.getPressure(), event.getToolType(0));
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!drawing) return true;
        for (int i = 0; i < event.getHistorySize(); i++)
          drawLine(
              event.getHistoricalX(i) * SIZE / getWidth(),
              event.getHistoricalY(i) * SIZE / getHeight(),
              event.getHistoricalPressure(i),
              event.getToolType(0));
        drawLine(x, y, event.getPressure(), event.getToolType(0));
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        drawing = false;
        getParent().requestDisallowInterceptTouchEvent(false);
        changed.run();
        performClick();
        return true;
      default:
        return true;
    }
  }

  private void drawLine(float x, float y, float pressure, int tool) {
    paint.setColor(eraser ? Color.WHITE : color);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeWidth(
        brushSize * (tool == MotionEvent.TOOL_TYPE_STYLUS ? Math.max(.15f, pressure) * 2 : 1));
    canvas.drawLine(lastX, lastY, x, y, paint);
    lastX = x;
    lastY = y;
    invalidate();
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
