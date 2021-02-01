/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.vision.demo.java.labeldetector;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.AsyncTask;
import android.util.Log;

import com.google.common.primitives.Floats;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.label.ImageLabel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/** Graphic instance for rendering a label within an associated graphic overlay view. */
public class LabelGraphic extends GraphicOverlay.Graphic {

  private static final float TEXT_SIZE = 70.0f;
  private static final String LABEL_FORMAT = "%.2f%% confidence (index: %d)";

  private final Paint textPaint;
  private final Paint labelPaint;
  private final GraphicOverlay overlay;

  private final List<ImageLabel> labels;

  int textBuffer = 1000;
  String txtJson;

  public LabelGraphic(GraphicOverlay overlay, List<ImageLabel> labels) {
    super(overlay);
    this.overlay = overlay;
    this.labels = labels;
    textPaint = new Paint();
    textPaint.setColor(Color.WHITE);
    textPaint.setTextSize(TEXT_SIZE);

    labelPaint = new Paint();
    labelPaint.setColor(Color.BLACK);
    labelPaint.setStyle(Paint.Style.FILL);
    labelPaint.setAlpha(200);
  }

  @Override
  public synchronized void draw(Canvas canvas) {
    // First try to find maxWidth and totalHeight in order to draw to the center of the screen.
    float maxWidth = 0;
    float totalHeight = labels.size() * 2 * TEXT_SIZE;
    for (ImageLabel label : labels) {
      float line1Width = textPaint.measureText(label.getText());
      float line2Width =
          textPaint.measureText(
              String.format(
                  Locale.US, LABEL_FORMAT, label.getConfidence() * 100, label.getIndex()));
      maxWidth = Floats.max(maxWidth, line1Width, line2Width);
    }
    float x = Math.max(0, overlay.getWidth() / 2.0f - maxWidth / 2.0f);
    float y = Math.max(200, overlay.getHeight() / 2.0f - totalHeight / 2.0f) + textBuffer;

    int times = 0;
    for (ImageLabel label : labels) {
      times++;
      if (times > 1){
        break;
      }
      if (y + TEXT_SIZE * 2 > overlay.getHeight()) {
        break;
      }

      String info = getJSONInfo(label.getText()).concat(" kcal");
      canvas.drawText(
              label.getText(),
              x,
              y + TEXT_SIZE,
              textPaint);
      y += TEXT_SIZE;
      canvas.drawText(
          info,
          x,
          y + TEXT_SIZE,
          textPaint);
      y += TEXT_SIZE;
    }
  }

  public String getJSONInfo(String keywords){
    String url = "https://api.calorieninjas.com/v1/nutrition?query=".concat(keywords);
    try {
      new JsonTask().execute(url).get();
      return parseJSON(txtJson);
    }
    catch (Exception e){
      e.printStackTrace();
      return null;
    }
  }

  public String parseJSON(String jsonText){
    try {
      JSONObject jsonObj = new JSONObject(jsonText);
      JSONArray nutritionInfo = jsonObj.getJSONArray("items");
      return Double.toString(nutritionInfo.getJSONObject(0).getDouble("calories"));
    }
    catch (Exception e){
      e.printStackTrace();
      return null;
    }
  }

  private class JsonTask extends AsyncTask<String, String, String> {

    protected void onPreExecute() {
      super.onPreExecute();
    }

    protected String doInBackground(String... params) {


      HttpURLConnection connection = null;
      BufferedReader reader = null;

      try {
        URL url = new URL(params[0]);
        connection = (HttpURLConnection) url.openConnection();

        connection.setRequestProperty("X-Api-Key", "WnL/g7XJpD61KNH/GOERsw==Qw3KEMwwleWbsAnG");

        connection.connect();

        InputStream stream = connection.getInputStream();

        reader = new BufferedReader(new InputStreamReader(stream));

        StringBuffer buffer = new StringBuffer();
        String line = "";

        while ((line = reader.readLine()) != null) {
          buffer.append(line+"\n");
          Log.d("Response: ", "> " + line);   //here u ll get whole response...... :-)

        }
        txtJson = buffer.toString();
        return buffer.toString();


      } catch (MalformedURLException e) {
        e.printStackTrace();
      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        if (connection != null) {
          connection.disconnect();
        }
        try {
          if (reader != null) {
            reader.close();
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
      return null;
    }

    @Override
    protected void onPostExecute(String result) {
      super.onPostExecute(result);
      txtJson = result;
    }
  }
}
