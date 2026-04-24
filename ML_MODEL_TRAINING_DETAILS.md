# Asfalis Machine Learning Model Training Documentation

This document provides a comprehensive overview of how the Asfalis anomaly/SOS detection machine learning model is trained, including the specific features extracted, the data pipeline format, preprocessing logic, and model conversion for mobile integration.

## 1. Overview of the Model

The Asfalis Auto-SOS system utilizes a lightweight **Neural Network** designed for **Edge AI / On-Device inference**. 
Initially prototyped using traditional ML algorithms (LightGBM / Random Forest) on a cloud backend, the model was subsequently migrated to a TensorFlow/Keras framework and converted to **TensorFlow Lite (TFLite)** to ensure zero-latency inference directly on the user's Android smartphone.

- **Model Goal:** Binary classification (0.0 = Safe, 1.0 = Danger/SOS).
- **Target Output:** Probability of a distress event occurring during a specific window of movement.
- **Inference Threshold:** `0.60` (Trigger SOS if probability $\ge$ 60%).

---

## 2. Dataset Collection & Formatting

The dataset is built entirely from human motion kinematics. Data is recorded via the **Asfalis Data Collector** application, connecting to an embedded IoT module (e.g., MPU6050 accelerometer/gyroscope on an ESP32).

### 2.1 Hardware Specifications
*   **Sensors Used:** Accelerometer (Linear acceleration) and Gyroscope (Angular velocity).
*   **Axes:** X, Y, Z for both sensors.
*   **Sampling Frequency:** 50 Hz (50 samples per second).

### 2.2 Dataset Rows & Windowing Strategy
Rather than training on single sensor ticks, the model is trained on **time-series "windows"**. 
*   **Window Size:** 300 points per window.
*   **Time Duration:** At 50 Hz, 300 points represent exactly **6 seconds** of continuous recording.
*   **Total Dataset Size:** Tens to hundreds of thousands of rows depending on the aggregation of CSV log files. Each "row" fed to the model during training logically represents a full 6-second physical event (e.g., a fall, running, walking, dropping the device, or an active physical distress struggle).

---

## 3. Data Feeding & Feature Engineering

The Neural Network **does not** ingest the raw (300 x 3) sensor buffers. Instead, to maximize efficiency and reduce the TFLite model size, the data pipeline extracts exactly **17 statistical features** from every 6-second window.

### 3.1 Feature Vector Shape
*   **Input Shape:** `(1, 17)`
*   **Data Type:** `float32`

### 3.2 The 17 Extracted Features
The raw X, Y, Z arrays within a 300-point window are aggregated into the following statistical summaries:
1.  `X_mean`: Average value across the X-axis.
2.  `X_std`: Standard deviation (variance) of the X-axis.
3.  `X_max`: Maximum peak absolute value on the X-axis.
4.  `X_min`: Minimum peak absolute value on the X-axis.
5.  `X_sum_sq`: Sum of squares (energy) across the X-axis.
6.  `Y_mean`
7.  `Y_std`
8.  `Y_max`
9.  `Y_min`
10. `Y_sum_sq`
11. `Z_mean`
12. `Z_std`
13. `Z_max`
14. `Z_min`
15. `Z_sum_sq`
16. `is_accelerometer`: One-hot encoded boolean (1.0 if the window is from the accelerometer, 0.0 otherwise).
17. `is_gyroscope`: One-hot encoded boolean (1.0 if the window is from the gyroscope, 0.0 otherwise).

---

## 4. Preprocessing & Normalization

Raw hardware values fluctuate massively depending on sensor calibration. To ensure the model trains accurately without gradient explosion, all features (except the one-hot identifiers) are normalized.

### StandardScaler Implementation
During the training pipeline, `sklearn.preprocessing.StandardScaler` is applied to fit the training data. The resulting `mean` and `scale` (standard deviation) arrays for all 17 features are exported to `model_metadata.json`.

**Normalization Formula:**
```math
X_{scaled} = \frac{X - \mu}{\sigma}
```

The Android app retrieves these precise $\mu$ (mean) and $\sigma$ (scale) values from the metadata to normalize live real-time sensor features identical to the training environment.

---

## 5. Training Strategy & TFLite Conversion

### 5.1 Training Architecture
1.  **Input Layer:** Fixed shape `(17,)` taking the scaled float32 feature array.
2.  **Hidden Layers:** Dense (Fully Connected) layers, likely paired with Dropout layers to prevent overfitting on the hardware kinematics.
3.  **Activation:** `ReLU` for hidden layers, culminating in a `Sigmoid` output activation.
4.  **Loss Function:** Binary Crossentropy.
5.  **Optimizer:** Adam.

### 5.2 Mobile Export (TensorFlow Lite)
Because standard `.keras` or SavedModels contain heavy gradients and dependencies not suited for Android devices, the trained model undergoes a transformation:

1.  **TFLiteConverter (`tf.lite.TFLiteConverter`)**: Converts the Keras model graph into a highly stripped-down flatbuffer format.
2.  **Post-Training Quantization:** The weights (originally 32-bit floats) are optimized/quantized down to 16-bit or 8-bit integers. This allows the resulting `auto_sos_mobile.tflite` model to be incredibly tiny (~10 KB in size) with minimal impact on accuracy.
3.  **Delivery:** The generated `.tflite` model, alongside its `model_metadata.json`, is packaged natively into the `app/src/main/assets/` folder of the Kotlin Android application.

---

## 6. Real-time Inference Summary
Once deployed:
1. The hardware collects data at 50Hz.
2. The phone groups 300 points (6 seconds) into a sliding window.
3. 17 statistical parameters are mathematical evaluated from the window.
4. Features are scaled via the saved metadata constants.
5. The TFLite runtime executes `Interpreter.run([1, 17])`.
6. Network outputs `[1, 1]` probability; `> 0.60` trips the SOS alarm.
