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

package com.google.mlkit.vision.demo.kotlin

import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import android.widget.ToggleButton
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.annotation.KeepName
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.demo.CameraXViewModel
import com.google.mlkit.vision.demo.GraphicOverlay
import com.google.mlkit.vision.demo.R
import com.google.mlkit.vision.demo.VisionImageProcessor
import com.google.mlkit.vision.demo.kotlin.barcodescanner.BarcodeScannerProcessor
import com.google.mlkit.vision.demo.kotlin.facedetector.FaceDetectorProcessor
import com.google.mlkit.vision.demo.kotlin.labeldetector.LabelDetectorProcessor
import com.google.mlkit.vision.demo.kotlin.objectdetector.ObjectDetectorProcessor
import com.google.mlkit.vision.demo.kotlin.posedetector.PoseDetectorProcessor
import com.google.mlkit.vision.demo.kotlin.textdetector.TextRecognitionProcessor
import com.google.mlkit.vision.demo.preference.PreferenceUtils
import com.google.mlkit.vision.demo.preference.SettingsActivity
import com.google.mlkit.vision.demo.preference.SettingsActivity.LaunchSource
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.ArrayList

/** Live preview demo app for ML Kit APIs using CameraX.  */
@KeepName
@RequiresApi(VERSION_CODES.LOLLIPOP)
class CameraXLivePreviewActivity :
  AppCompatActivity(),
  ActivityCompat.OnRequestPermissionsResultCallback,
  OnItemSelectedListener,
  CompoundButton.OnCheckedChangeListener {

  private var previewView: PreviewView? = null
  private var graphicOverlay: GraphicOverlay? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private var previewUseCase: Preview? = null
  private var analysisUseCase: ImageAnalysis? = null
  private var imageProcessor: VisionImageProcessor? = null
  private var needUpdateGraphicOverlayImageSourceInfo = false
  private var selectedModel = OBJECT_DETECTION
  private var lensFacing = CameraSelector.LENS_FACING_BACK
  private var cameraSelector: CameraSelector? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(TAG, "onCreate")
    if (VERSION.SDK_INT < VERSION_CODES.LOLLIPOP) {
      Toast.makeText(
        applicationContext,
        "CameraX is only supported on SDK version >=21. Current SDK version is " +
          VERSION.SDK_INT,
        Toast.LENGTH_LONG
      )
        .show()
      return
    }
    if (savedInstanceState != null) {
      selectedModel =
        savedInstanceState.getString(
          STATE_SELECTED_MODEL,
          OBJECT_DETECTION
        )
      lensFacing =
        savedInstanceState.getInt(
          STATE_LENS_FACING,
          CameraSelector.LENS_FACING_BACK
        )
    }
    cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
    setContentView(R.layout.activity_vision_camerax_live_preview)
    previewView = findViewById(R.id.preview_view)
    if (previewView == null) {
      Log.d(TAG, "previewView is null")
    }
    graphicOverlay = findViewById(R.id.graphic_overlay)
    if (graphicOverlay == null) {
      Log.d(TAG, "graphicOverlay is null")
    }
    val spinner = findViewById<Spinner>(R.id.spinner)
    val options: MutableList<String> = ArrayList()
    options.add(OBJECT_DETECTION)
    options.add(OBJECT_DETECTION_CUSTOM)
    options.add(CUSTOM_AUTOML_OBJECT_DETECTION)
    options.add(FACE_DETECTION)
    options.add(TEXT_RECOGNITION)
    options.add(BARCODE_SCANNING)
    options.add(IMAGE_LABELING)
    options.add(IMAGE_LABELING_CUSTOM)
    options.add(CUSTOM_AUTOML_LABELING)
    options.add(POSE_DETECTION)

    // Creating adapter for spinner
    val dataAdapter =
      ArrayAdapter(this, R.layout.spinner_style, options)
    // Drop down layout style - list view with radio button
    dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    // attaching data adapter to spinner
    spinner.adapter = dataAdapter
    spinner.onItemSelectedListener = this
    val facingSwitch =
      findViewById<ToggleButton>(R.id.facing_switch)
    facingSwitch.setOnCheckedChangeListener(this)
    ViewModelProvider(
      this,
      ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    )
      .get(CameraXViewModel::class.java)
      .processCameraProvider
      .observe(
        this,
        Observer { provider: ProcessCameraProvider? ->
          cameraProvider = provider
          if (allPermissionsGranted()) {
            bindAllCameraUseCases()
          }
        }
      )

    val settingsButton = findViewById<ImageView>(R.id.settings_button)
    settingsButton.setOnClickListener {
      val intent =
        Intent(applicationContext, SettingsActivity::class.java)
      intent.putExtra(
        SettingsActivity.EXTRA_LAUNCH_SOURCE,
        LaunchSource.CAMERAX_LIVE_PREVIEW
      )
      startActivity(intent)
    }

    if (!allPermissionsGranted()) {
      runtimePermissions
    }
  }

  override fun onSaveInstanceState(bundle: Bundle) {
    super.onSaveInstanceState(bundle)
    bundle.putString(STATE_SELECTED_MODEL, selectedModel)
    bundle.putInt(STATE_LENS_FACING, lensFacing)
  }

  @Synchronized
  override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
    // An item was selected. You can retrieve the selected item using
    // parent.getItemAtPosition(pos)
    selectedModel = parent?.getItemAtPosition(pos).toString()
    Log.d(TAG, "Selected model: $selectedModel")
    bindAnalysisUseCase()
  }

  override fun onNothingSelected(parent: AdapterView<*>?) {
    // Do nothing.
  }

  override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
    Log.d(TAG, "Set facing")
    if (cameraProvider == null) {
      return
    }
    val newLensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
      CameraSelector.LENS_FACING_BACK
    } else {
      CameraSelector.LENS_FACING_FRONT
    }
    val newCameraSelector =
      CameraSelector.Builder().requireLensFacing(newLensFacing).build()
    try {
      if (cameraProvider!!.hasCamera(newCameraSelector)) {
        lensFacing = newLensFacing
        cameraSelector = newCameraSelector
        bindAllCameraUseCases()
        return
      }
    } catch (e: CameraInfoUnavailableException) {
      // Falls through
    }
    Toast.makeText(
      applicationContext, "This device does not have lens with facing: $newLensFacing",
      Toast.LENGTH_SHORT
    )
      .show()
  }

  public override fun onResume() {
    super.onResume()
    bindAllCameraUseCases()
  }

  override fun onPause() {
    super.onPause()

    imageProcessor?.run {
      this.stop()
    }
  }

  public override fun onDestroy() {
    super.onDestroy()
    imageProcessor?.run {
      this.stop()
    }
  }

  private fun bindAllCameraUseCases() {
    if (cameraProvider != null) {
      // As required by CameraX API, unbinds all use cases before trying to re-bind any of them.
      cameraProvider!!.unbindAll()
      bindPreviewUseCase()
      bindAnalysisUseCase()
    }
  }

  private fun bindPreviewUseCase() {
    if (!PreferenceUtils.isCameraLiveViewportEnabled(this)) {
      return
    }
    if (cameraProvider == null) {
      return
    }
    if (previewUseCase != null) {
      cameraProvider!!.unbind(previewUseCase)
    }

    val builder = Preview.Builder()
    val targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing)
    if (targetResolution != null) {
      builder.setTargetResolution(targetResolution)
    }
    previewUseCase = builder.build()
    previewUseCase!!.setSurfaceProvider(previewView!!.getSurfaceProvider())
    cameraProvider!!.bindToLifecycle(/* lifecycleOwner= */this, cameraSelector!!, previewUseCase)
  }

  private fun bindAnalysisUseCase() {
    if (cameraProvider == null) {
      return
    }
    if (analysisUseCase != null) {
      cameraProvider!!.unbind(analysisUseCase)
    }
    if (imageProcessor != null) {
      imageProcessor!!.stop()
    }
    imageProcessor = try {
      when (selectedModel) {
        OBJECT_DETECTION -> {
          Log.i(
            TAG,
            "Using Object Detector Processor"
          )
          val objectDetectorOptions =
            PreferenceUtils.getObjectDetectorOptionsForLivePreview(this)
          ObjectDetectorProcessor(
            this, objectDetectorOptions
          )
        }
        OBJECT_DETECTION_CUSTOM -> {
          Log.i(
            TAG,
            "Using Custom Object Detector (Bird) Processor"
          )
          val localModel = LocalModel.Builder()
            .setAssetFilePath("custom_models/bird_classifier.tflite")
            .build()
          val customObjectDetectorOptions =
            PreferenceUtils.getCustomObjectDetectorOptionsForLivePreview(this, localModel)
          ObjectDetectorProcessor(
            this, customObjectDetectorOptions
          )
        }
        CUSTOM_AUTOML_OBJECT_DETECTION -> {
          Log.i(
            TAG,
            "Using Custom AutoML Object Detector Processor"
          )
          val customAutoMLODTLocalModel = LocalModel.Builder()
            .setAssetManifestFilePath("automl/manifest.json")
            .build()
          val customAutoMLODTOptions = PreferenceUtils
            .getCustomObjectDetectorOptionsForLivePreview(this, customAutoMLODTLocalModel)
          ObjectDetectorProcessor(
            this, customAutoMLODTOptions
          )
        }
        TEXT_RECOGNITION -> {
          Log.i(
            TAG,
            "Using on-device Text recognition Processor"
          )
          TextRecognitionProcessor(this)
        }
        FACE_DETECTION -> {
          Log.i(
            TAG,
            "Using Face Detector Processor"
          )
          val faceDetectorOptions =
            PreferenceUtils.getFaceDetectorOptionsForLivePreview(this)
          FaceDetectorProcessor(this, faceDetectorOptions)
        }
        BARCODE_SCANNING -> {
          Log.i(
            TAG,
            "Using Barcode Detector Processor"
          )
          BarcodeScannerProcessor(this)
        }
        IMAGE_LABELING -> {
          Log.i(
            TAG,
            "Using Image Label Detector Processor"
          )
          LabelDetectorProcessor(
            this, ImageLabelerOptions.DEFAULT_OPTIONS
          )
        }
        IMAGE_LABELING_CUSTOM -> {
          Log.i(
            TAG,
            "Using Custom Image Label (Bird) Detector Processor"
          )
          val localClassifier = LocalModel.Builder()
            .setAssetFilePath("custom_models/bird_classifier.tflite")
            .build()
          val customImageLabelerOptions =
            CustomImageLabelerOptions.Builder(localClassifier).build()
          LabelDetectorProcessor(
            this, customImageLabelerOptions
          )
        }
        CUSTOM_AUTOML_LABELING -> {
          Log.i(
            TAG,
            "Using Custom AutoML Image Label Detector Processor"
          )
          val customAutoMLLabelLocalModel = LocalModel.Builder()
            .setAssetManifestFilePath("automl/manifest.json")
            .build()
          val customAutoMLLabelOptions = CustomImageLabelerOptions
            .Builder(customAutoMLLabelLocalModel)
            .setConfidenceThreshold(0f)
            .build()
          LabelDetectorProcessor(
            this, customAutoMLLabelOptions
          )
        }
        POSE_DETECTION -> {
          val poseDetectorOptions =
            PreferenceUtils.getPoseDetectorOptionsForLivePreview(this)
          val shouldShowInFrameLikelihood =
            PreferenceUtils.shouldShowPoseDetectionInFrameLikelihoodLivePreview(this)
          val visualizeZ = PreferenceUtils.shouldPoseDetectionVisualizeZ(this)
          val rescaleZ = PreferenceUtils.shouldPoseDetectionRescaleZForVisualization(this)
          PoseDetectorProcessor(
            this, poseDetectorOptions, shouldShowInFrameLikelihood, visualizeZ, rescaleZ
          )
        }
        else -> throw IllegalStateException("Invalid model name")
      }
    } catch (e: Exception) {
      Log.e(
        TAG,
        "Can not create image processor: $selectedModel",
        e
      )
      Toast.makeText(
        applicationContext,
        "Can not create image processor: " + e.localizedMessage,
        Toast.LENGTH_LONG
      )
        .show()
      return
    }

    val builder = ImageAnalysis.Builder()
    val targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing)
    if (targetResolution != null) {
      builder.setTargetResolution(targetResolution)
    }
    analysisUseCase = builder.build()

    needUpdateGraphicOverlayImageSourceInfo = true

    analysisUseCase?.setAnalyzer(
      // imageProcessor.processImageProxy will use another thread to run the detection underneath,
      // thus we can just runs the analyzer itself on main thread.
      ContextCompat.getMainExecutor(this),
      ImageAnalysis.Analyzer { imageProxy: ImageProxy ->
        if (needUpdateGraphicOverlayImageSourceInfo) {
          val isImageFlipped =
            lensFacing == CameraSelector.LENS_FACING_FRONT
          val rotationDegrees =
            imageProxy.imageInfo.rotationDegrees
          if (rotationDegrees == 0 || rotationDegrees == 180) {
            graphicOverlay!!.setImageSourceInfo(
              imageProxy.width, imageProxy.height, isImageFlipped
            )
          } else {
            graphicOverlay!!.setImageSourceInfo(
              imageProxy.height, imageProxy.width, isImageFlipped
            )
          }
          needUpdateGraphicOverlayImageSourceInfo = false
        }
        try {
          imageProcessor!!.processImageProxy(imageProxy, graphicOverlay)
        } catch (e: MlKitException) {
          Log.e(
            TAG,
            "Failed to process image. Error: " + e.localizedMessage
          )
          Toast.makeText(
            applicationContext,
            e.localizedMessage,
            Toast.LENGTH_SHORT
          )
            .show()
        }
      }
    )
    cameraProvider!!.bindToLifecycle( /* lifecycleOwner= */this, cameraSelector!!, analysisUseCase)
  }

  private val requiredPermissions: Array<String?>
    get() = try {
      val info = this.packageManager
        .getPackageInfo(this.packageName, PackageManager.GET_PERMISSIONS)
      val ps = info.requestedPermissions
      if (ps != null && ps.isNotEmpty()) {
        ps
      } else {
        arrayOfNulls(0)
      }
    } catch (e: Exception) {
      arrayOfNulls(0)
    }

  private fun allPermissionsGranted(): Boolean {
    for (permission in requiredPermissions) {
      if (!isPermissionGranted(this, permission)) {
        return false
      }
    }
    return true
  }

  private val runtimePermissions: Unit
    get() {
      val allNeededPermissions: MutableList<String?> = ArrayList()
      for (permission in requiredPermissions) {
        if (!isPermissionGranted(this, permission)) {
          allNeededPermissions.add(permission)
        }
      }
      if (allNeededPermissions.isNotEmpty()) {
        ActivityCompat.requestPermissions(
          this,
          allNeededPermissions.toTypedArray(),
          PERMISSION_REQUESTS
        )
      }
    }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    Log.i(TAG, "Permission granted!")
    if (allPermissionsGranted()) {
      bindAllCameraUseCases()
    }
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
  }

  companion object {
    private const val TAG = "CameraXLivePreview"
    private const val PERMISSION_REQUESTS = 1
    private const val OBJECT_DETECTION = "Object Detection"
    private const val OBJECT_DETECTION_CUSTOM = "Custom Object Detection (Bird)"
    private const val CUSTOM_AUTOML_OBJECT_DETECTION = "Custom AutoML Object Detection (Flower)"
    private const val FACE_DETECTION = "Face Detection"
    private const val TEXT_RECOGNITION = "Text Recognition"
    private const val BARCODE_SCANNING = "Barcode Scanning"
    private const val IMAGE_LABELING = "Image Labeling"
    private const val IMAGE_LABELING_CUSTOM = "Custom Image Labeling (Bird)"
    private const val CUSTOM_AUTOML_LABELING = "Custom AutoML Image Labeling (Flower)"
    private const val POSE_DETECTION = "Pose Detection"

    private const val STATE_SELECTED_MODEL = "selected_model"
    private const val STATE_LENS_FACING = "lens_facing"

    private fun isPermissionGranted(
      context: Context,
      permission: String?
    ): Boolean {
      if (ContextCompat.checkSelfPermission(context, permission!!)
        == PackageManager.PERMISSION_GRANTED
      ) {
        Log.i(TAG, "Permission granted: $permission")
        return true
      }
      Log.i(TAG, "Permission NOT granted: $permission")
      return false
    }
  }
}
