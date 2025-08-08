package com.cordova.plugin;
//Sium
// Aggiungi questo importo in cima al file
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbInterface;
import java.util.HashMap;
import org.json.JSONException;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class UsbExternalCamera extends CordovaPlugin {
    private static final String TAG = "UsbExternalCamera";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    
    private String externalCameraId;
    private CallbackContext frameCallback;
    private CallbackContext errorCallback;
    
    private int previewWidth = 1280;
    private int previewHeight = 720;
    private int previewFps = 30;
    
    private boolean isPreviewActive = false;
    private CallbackContext pendingOpenCallback;
    private JSONArray pendingOpenArgs;
    private boolean autofocusDisabled = false;
    
    private UsbDeviceConnection uvcConnection;
    private UsbInterface videoControlInterface;
    private int focusAbsoluteMin = 0;
    private int focusAbsoluteMax = 255;
    // UVC discovery data
    private UsbDevice uvcDevice;
    private int vcInterfaceNumber = -1; // bInterfaceNumber for VideoControl
    private int cameraTerminalId = -1;  // bTerminalID for ITT_CAMERA
    private int processingUnitId = -1;  // bUnitID for Processing Unit (optional)
    private int exposureAbsoluteMin = 0;
    private int exposureAbsoluteMax = 0;
    private int brightnessMin = 0;
    private int brightnessMax = 0;
    
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        switch (action) {
            case "open":
                return openCamera(args, callbackContext);
            case "initSimple":
                return initSimple(callbackContext);
            case "stopPreview":
                return stopPreview(callbackContext);
            case "takePhoto":
                return takePhoto(callbackContext);
            case "close":
                return closeCamera(callbackContext);
            case "listCameras":
                return listCameras(callbackContext);
            case "disableAutofocus":
                return disableAutofocus(callbackContext);
            case "triggerAutofocus":
                return triggerAutofocus(callbackContext);
            case "optimizeAutofocusForUsb":
                return optimizeAutofocusForUsb(callbackContext);
            case "debugCapabilities":
                return debugCameraCapabilities(callbackContext);
            case "setFocusDistance":
                return setFocusDistance(args, callbackContext);
            case "setUvcAutoFocus":
                return setUvcAutoFocus(args, callbackContext);
            case "setUvcFocusAbsolute":
                return setUvcFocusAbsolute(args, callbackContext);
            case "debugUvcControls":
                return debugUvcControls(callbackContext);
            case "setUvcAutoExposure":
                return setUvcAutoExposure(args, callbackContext);
            case "setUvcExposureAbsolute":
                return setUvcExposureAbsolute(args, callbackContext);
            case "debugUvcExposure":
                return debugUvcExposure(callbackContext);
            case "setUvcBrightness":
                return setUvcBrightness(args, callbackContext);
            case "debugUvcBrightness":
                return debugUvcBrightness(callbackContext);
            case "recoverCamera":
                return recoverCamera(callbackContext);
            default:
                return false;
        }
    }

    private boolean openCamera(JSONArray args, CallbackContext callbackContext) throws JSONException {
        JSONObject options = args.optJSONObject(0);
        if (options != null) {
            previewWidth = options.optInt("width", 1280);
            previewHeight = options.optInt("height", 720);
            previewFps = options.optInt("fps", 30);
            
            String requestedCameraId = options.optString("cameraId", null);
            if (requestedCameraId != null && !requestedCameraId.isEmpty()) {
                externalCameraId = requestedCameraId;
            }
        }
        
        frameCallback = callbackContext;
        
        cordova.getThreadPool().execute(() -> {
            try {
                initializeCamera();
            } catch (Exception e) {
                Log.e(TAG, "Error opening camera", e);
                if (frameCallback != null) {
                    frameCallback.error("Failed to open camera: " + e.getMessage());
                }
            }
        });
        
        return true;
    }
    
    private boolean checkUsbPermissions() {
        UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
        return usbManager != null;
    }
    
    private boolean stopPreview(CallbackContext callbackContext) {
        try {
            isPreviewActive = false;
            if (captureSession != null) {
                captureSession.stopRepeating();
            }
            callbackContext.success("Preview stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping preview", e);
            callbackContext.error("Failed to stop preview: " + e.getMessage());
        }
        return true;
    }

    private boolean takePhoto(CallbackContext callbackContext) {
        if (cameraDevice == null) {
            callbackContext.error("Camera not opened");
            return true;
        }

        cordova.getThreadPool().execute(() -> {
            try {
                captureStillPicture(callbackContext);
            } catch (Exception e) {
                Log.e(TAG, "Error taking photo", e);
                callbackContext.error("Failed to take photo: " + e.getMessage());
            }
        });
        
        return true;
    }

    private boolean closeCamera(CallbackContext callbackContext) {
        try {
            closeBackgroundThread();
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            
            if (uvcConnection != null) {
                try {
                    if (videoControlInterface != null) {
                        uvcConnection.releaseInterface(videoControlInterface);
                    }
                    uvcConnection.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing UVC connection", e);
                }
                uvcConnection = null;
                videoControlInterface = null;
            }
            
            isPreviewActive = false;
            frameCallback = null;
            callbackContext.success("Camera closed");
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera", e);
            callbackContext.error("Failed to close camera: " + e.getMessage());
        }
        return true;
    }

    private void initializeCamera() throws CameraAccessException {
        cameraManager = (CameraManager) cordova.getActivity().getSystemService(Context.CAMERA_SERVICE);
        
        String[] cameraIds = cameraManager.getCameraIdList();
        Log.d(TAG, "Found " + cameraIds.length + " cameras: " + Arrays.toString(cameraIds));
        
        // Se è specificato un cameraId, validalo prima di usarlo
        if (externalCameraId != null && !externalCameraId.isEmpty()) {
            // Verifica che l'ID esista
            boolean cameraExists = false;
            for (String id : cameraIds) {
                if (id.equals(externalCameraId)) {
                    cameraExists = true;
                    break;
                }
            }
            
            if (cameraExists) {
                Log.d(TAG, "Using specified camera ID: " + externalCameraId);
                startBackgroundThread();
                openCameraDevice();
                return;
            } else {
                Log.w(TAG, "Specified camera ID " + externalCameraId + " not found. Available IDs: " + Arrays.toString(cameraIds));
                throw new RuntimeException("Camera ID " + externalCameraId + " not found. Available cameras: " + Arrays.toString(cameraIds));
            }
        }
        
        // ← NUOVO: Cerca USB camere con il metodo avanzato
        for (String cameraId : cameraIds) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            
            if (isUsbExternalCamera(characteristics, cameraId)) {
                externalCameraId = cameraId;
                Log.d(TAG, "Found USB external camera: " + cameraId);
                break;
            }
        }
        
        if (externalCameraId == null) {
            throw new RuntimeException("No USB external camera found. Available cameras: " + Arrays.toString(cameraIds) + ". Use listCameras() to see detailed info and specify cameraId in options.");
        }

        Log.d(TAG, "Selected camera: " + externalCameraId);
        startBackgroundThread();
        openCameraDevice();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void closeBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error closing background thread", e);
            }
        }
    }

    private void openCameraDevice() throws CameraAccessException {
        if (ActivityCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        
        cameraManager.openCamera(externalCameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                cameraDevice = camera;
                try {
                    createCameraPreviewSession();
                } catch (Exception e) {
                    Log.e(TAG, "Error creating preview session", e);
                    if (frameCallback != null) {
                        frameCallback.error("Failed to create preview session: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                camera.close();
                cameraDevice = null;
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                camera.close();
                cameraDevice = null;
                if (frameCallback != null) {
                    frameCallback.error("Camera error: " + error);
                }
            }
        }, backgroundHandler);
    }

    private void createCameraPreviewSession() throws CameraAccessException {
        imageReader = ImageReader.newInstance(previewWidth, previewHeight, ImageFormat.YUV_420_888, 2);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                if (isPreviewActive && frameCallback != null) {
                    Image image = reader.acquireLatestImage();
                    if (image != null) {
                        try {
                            String base64Frame = convertImageToBase64(image);
                            PluginResult result = new PluginResult(PluginResult.Status.OK, base64Frame);
                            result.setKeepCallback(true);
                            frameCallback.sendPluginResult(result);
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing frame", e);
                        } finally {
                            image.close();
                        }
                    }
                }
            }
        }, backgroundHandler);

        CaptureRequest.Builder previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        previewRequestBuilder.addTarget(imageReader.getSurface());

        cameraDevice.createCaptureSession(Arrays.asList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        if (cameraDevice == null) return;
                        
                        captureSession = session;
                        try {
                            if (autofocusDisabled) {
                            Log.d(TAG, "Setting AF_MODE_OFF for preview");
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                            
                            // Imposta focus manuale
                            Float minFocusDistance = cameraManager.getCameraCharacteristics(externalCameraId)
                                    .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                            if (minFocusDistance != null && minFocusDistance > 0) {
                                previewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
                                Log.d(TAG, "Set manual focus distance to infinity");
                            }
                            
                            // Disabilita trigger autofocus
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                        } else {
                            // MIGLIORAMENTO: Usa AUTO invece di CONTINUOUS per webcam USB
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                            
                            // Imposta parametri ottimizzati per webcam USB
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                            
                            // Riduce la sensibilità dell'autofocus
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                            
                            // Stabilizza l'esposizione per aiutare l'autofocus
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                            
                            Log.d(TAG, "Using optimized AF_MODE_AUTO for USB webcam");
                        }
                            CaptureRequest previewRequest = previewRequestBuilder.build();
                            captureSession.setRepeatingRequest(previewRequest, null, backgroundHandler);
                            isPreviewActive = true;
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Error starting preview", e);
                            if (frameCallback != null) {
                                frameCallback.error("Failed to start preview: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        if (frameCallback != null) {
                            frameCallback.error("Failed to configure camera session");
                        }
                    }
                }, null);
    }

    private void captureStillPicture(CallbackContext callbackContext) {
        try {
            if (cameraDevice == null) {
                callbackContext.error("Camera not available");
                return;
            }

            ImageReader stillReader = ImageReader.newInstance(previewWidth, previewHeight, ImageFormat.JPEG, 1);
            stillReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    if (image != null) {
                        try {
                            String filePath = saveImageToFile(image);
                            callbackContext.success(filePath);
                        } catch (Exception e) {
                            Log.e(TAG, "Error saving photo", e);
                            callbackContext.error("Failed to save photo: " + e.getMessage());
                        } finally {
                            image.close();
                            stillReader.close();
                        }
                    }
                }
            }, backgroundHandler);

            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(stillReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            cameraDevice.createCaptureSession(Arrays.asList(stillReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.capture(captureBuilder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error capturing photo", e);
                                callbackContext.error("Failed to capture photo: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            callbackContext.error("Failed to configure capture session");
                        }
                    }, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "Error in captureStillPicture", e);
            callbackContext.error("Failed to take photo: " + e.getMessage());
        }
    }

    private String convertImageToBase64(Image image) throws IOException {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 80, out);
        byte[] jpegBytes = out.toByteArray();
        
        return Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
    }

    private String saveImageToFile(Image image) throws IOException {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "USB_CAM_" + timeStamp + ".jpg";
        
        File storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "UsbCamera");
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        
        File photoFile = new File(storageDir, fileName);
        
        try (FileOutputStream output = new FileOutputStream(photoFile)) {
            output.write(bytes);
        }
        
        return photoFile.getAbsolutePath();
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        String[] permissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        cordova.requestPermissions(this, PERMISSION_REQUEST_CODE, permissions);
    }

    private String getFacingName(Integer lensFacing) {
        if (lensFacing == null) return "UNKNOWN";
        switch (lensFacing) {
            case CameraCharacteristics.LENS_FACING_FRONT: return "FRONT";
            case CameraCharacteristics.LENS_FACING_BACK: return "BACK";
            case CameraCharacteristics.LENS_FACING_EXTERNAL: return "EXTERNAL";
            default: return "OTHER(" + lensFacing + ")";
        }
    }
    // ← NUOVO METODO: Identificazione affidabile USB camera
    private boolean isUsbExternalCamera(CameraCharacteristics characteristics, String cameraId) {
        try {
            // 1. Controlla se è esplicitamente EXTERNAL
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                return true;
            }
            
            // 2. Controlla le capacità hardware tipiche delle USB camere
            int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities != null) {
                // USB camere spesso NON hanno certe capacità avanzate
                boolean hasAdvancedFeatures = false;
                for (int capability : capabilities) {
                    if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR ||
                        capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING ||
                        capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                        hasAdvancedFeatures = true;
                        break;
                    }
                }
                // Se NON ha funzioni avanzate, probabilmente è USB
                if (!hasAdvancedFeatures && capabilities.length <= 2) {
                    Log.d(TAG, "Camera " + cameraId + " identified as USB (limited capabilities)");
                    return true;
                }
            }
            
            // 3. Controlla i formati supportati
            StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configMap != null) {
                Size[] jpegSizes = configMap.getOutputSizes(ImageFormat.JPEG);
                Size[] yuvSizes = configMap.getOutputSizes(ImageFormat.YUV_420_888);
                
                // USB camere spesso hanno limitazioni sui formati
                if (jpegSizes != null && yuvSizes != null) {
                    // Se ha pochi formati o risoluzioni limitate, probabilmente è USB
                    if (jpegSizes.length < 10 || yuvSizes.length < 5) {
                        Log.d(TAG, "Camera " + cameraId + " identified as USB (limited formats)");
                        return true;
                    }
                }
            }
            
            // 4. Controlla il supporto autofocus
            int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (afModes != null && afModes.length <= 1) {
                // USB camere spesso hanno autofocus limitato o assente
                Log.d(TAG, "Camera " + cameraId + " identified as USB (limited autofocus)");
                return true;
            }
            
            // 5. Controlla l'ID numerico alto (USB camere spesso hanno ID > 1)
            try {
                int numericId = Integer.parseInt(cameraId);
                if (numericId >= 2) {
                    Log.d(TAG, "Camera " + cameraId + " possibly USB (high ID number)");
                    return true;
                }
            } catch (NumberFormatException e) {
                // ID non numerico, potrebbe essere USB
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error analyzing camera " + cameraId, e);
            return false;
        }
    }

    // ← NUOVO METODO: Informazioni dettagliate per debug
    private JSONObject getDeviceInfo(CameraCharacteristics characteristics) {
        JSONObject info = new JSONObject();
        try {
            // Capacità
            int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            JSONArray capsArray = new JSONArray();
            if (capabilities != null) {
                for (int cap : capabilities) {
                    capsArray.put(cap);
                }
            }
            info.put("capabilities", capsArray);
            
            // Modalità autofocus
            int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            JSONArray afArray = new JSONArray();
            if (afModes != null) {
                for (int mode : afModes) {
                    afArray.put(mode);
                }
            }
            info.put("autofocusModes", afArray);
            
            // Formati supportati
            StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configMap != null) {
                Size[] jpegSizes = configMap.getOutputSizes(ImageFormat.JPEG);
                info.put("jpegSizesCount", jpegSizes != null ? jpegSizes.length : 0);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting device info", e);
        }
        return info;
    }

    // ← AGGIUNGI QUESTO METODO MANCANTE
    private boolean isSimpleUsbCamera(CameraCharacteristics characteristics, String cameraId) {
        try {
            // 1. Controlla se è esplicitamente EXTERNAL
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                return true;
            }
            
            // 2. Se l'ID è >= 2, probabilmente è USB
            try {
                int numericId = Integer.parseInt(cameraId);
                return numericId >= 2;
            } catch (NumberFormatException e) {
                // ID non numerico, potrebbe essere USB
                return true;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking USB camera: " + cameraId, e);
            return false;
        }
    }

    // ← AGGIUNGI ANCHE QUESTO METODO
    private String getDeviceName(CameraCharacteristics characteristics, String cameraId) {
        try {
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            
            if (lensFacing != null) {
                switch (lensFacing) {
                    case CameraCharacteristics.LENS_FACING_FRONT:
                        return "Front Camera (ID: " + cameraId + ")";
                    case CameraCharacteristics.LENS_FACING_BACK:
                        return "Back Camera (ID: " + cameraId + ")";
                    case CameraCharacteristics.LENS_FACING_EXTERNAL:
                        return "USB Camera (ID: " + cameraId + ")";
                    default:
                        return "Camera " + cameraId;
                }
            }
            
            return "Camera " + cameraId;
            
        } catch (Exception e) {
            return "Camera " + cameraId;
        }
    }
    
    // ← NUOVO METODO: Ottieni informazioni dettagliate USB
    private JSONObject getUsbDeviceInfo(String cameraId) {
        JSONObject usbInfo = new JSONObject();
        try {
            UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            
            // Cerca dispositivi video USB
            for (UsbDevice device : deviceList.values()) {
                // Controlla se è un dispositivo video (classe 14 = Video)
                if (device.getDeviceClass() == 14 || 
                    device.getDeviceClass() == 239 || // Miscellaneous Device
                    hasVideoInterface(device)) {
                    
                    String deviceName = device.getProductName();
                    String manufacturerName = device.getManufacturerName();
                    
                    // Se troviamo una corrispondenza con l'ID camera
                    if (deviceName != null && manufacturerName != null) {
                        usbInfo.put("productName", deviceName);
                        usbInfo.put("manufacturerName", manufacturerName);
                        usbInfo.put("vendorId", device.getVendorId());
                        usbInfo.put("productId", device.getProductId());
                        usbInfo.put("deviceName", device.getDeviceName());
                        usbInfo.put("fullName", manufacturerName + " " + deviceName);
                        
                        // Identifica specificamente Logitech
                        if (manufacturerName.toLowerCase().contains("logitech") || 
                            deviceName.toLowerCase().contains("logitech")) {
                            usbInfo.put("isLogitech", true);
                            usbInfo.put("displayName", "Logitech " + deviceName);
                        } else {
                            usbInfo.put("isLogitech", false);
                            usbInfo.put("displayName", manufacturerName + " " + deviceName);
                        }
                        
                        Log.d(TAG, "Found USB camera: " + manufacturerName + " " + deviceName);
                        break;
                    }
                }
            }
            
            // Se non troviamo info USB, usa fallback
            if (!usbInfo.has("productName")) {
                usbInfo.put("displayName", "USB Camera " + cameraId);
                usbInfo.put("isLogitech", false);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting USB device info", e);
            try {
                usbInfo.put("displayName", "USB Camera " + cameraId);
                usbInfo.put("isLogitech", false);
            } catch (JSONException je) {
                // Ignore
            }
        }
        return usbInfo;
    }

    // ← METODO HELPER: Controlla se ha interfaccia video
    private boolean hasVideoInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface usbInterface = device.getInterface(i);
            // Classe 14 = Video, Sottoclasse 1 = Video Control, 2 = Video Streaming
            if (usbInterface.getInterfaceClass() == 14) {
                return true;
            }
        }
        return false;
    }

    // ← AGGIORNA IL METODO listCameras
    private boolean listCameras(CallbackContext callbackContext) {
        try {
            CameraManager manager = (CameraManager) cordova.getActivity().getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = manager.getCameraIdList();
            
            JSONArray cameras = new JSONArray();
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                
                JSONObject camera = new JSONObject();
                camera.put("id", cameraId);
                camera.put("isUsbCamera", isSimpleUsbCamera(characteristics, cameraId));
                
                if (isSimpleUsbCamera(characteristics, cameraId)) {
                    // ← NUOVO: Ottieni info USB dettagliate
                    JSONObject usbInfo = getUsbDeviceInfo(cameraId);
                    camera.put("name", usbInfo.optString("displayName", "USB Camera " + cameraId));
                    camera.put("manufacturer", usbInfo.optString("manufacturerName", "Unknown"));
                    camera.put("product", usbInfo.optString("productName", "Unknown"));
                    camera.put("isLogitech", usbInfo.optBoolean("isLogitech", false));
                    camera.put("vendorId", usbInfo.optInt("vendorId", 0));
                    camera.put("productId", usbInfo.optInt("productId", 0));
                } else {
                    camera.put("name", getDeviceName(characteristics, cameraId));
                    camera.put("isLogitech", false);
                }
                
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                camera.put("lensFacing", lensFacing);
                camera.put("facingName", getFacingName(lensFacing));
                
                cameras.put(camera);
            }
            
            callbackContext.success(cameras);
        } catch (Exception e) {
            callbackContext.error("Error listing cameras: " + e.getMessage());
        }
        return true;
    }
    private boolean disableAutofocus(CallbackContext callbackContext) {
        try {
            // Verifica se la camera supporta AF_MODE_OFF
            if (cameraDevice != null) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(externalCameraId);
                int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                
                boolean supportsAfOff = false;
                if (afModes != null) {
                    for (int mode : afModes) {
                        Log.d(TAG, "Available AF mode: " + mode);
                        if (mode == CameraCharacteristics.CONTROL_AF_MODE_OFF) {
                            supportsAfOff = true;
                            break;
                        }
                    }
                }
                
                if (!supportsAfOff) {
                    Log.w(TAG, "Camera does not support AF_MODE_OFF, trying alternative approach");
                    return disableAutofocusAlternative(callbackContext);
                }
            }
            
            autofocusDisabled = true;
            Log.d(TAG, "Autofocus disabled flag set to true");
            
            // Se il preview è già attivo, riavvia con AF disabilitato
            if (captureSession != null && cameraDevice != null && imageReader != null) {
                Log.d(TAG, "Restarting capture session with autofocus disabled");
                captureSession.stopRepeating();
                
                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(imageReader.getSurface());
                
                // Imposta AF_MODE_OFF
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                
                // Imposta focus manuale all'infinito
                Float minFocusDistance = cameraManager.getCameraCharacteristics(externalCameraId)
                        .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                if (minFocusDistance != null && minFocusDistance > 0) {
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f); // Infinito
                    Log.d(TAG, "Set manual focus to infinity (0.0f), min focus distance: " + minFocusDistance);
                }
                
                // Disabilita anche la stabilizzazione ottica se presente
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 
                           CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                
                // Imposta controllo manuale dell'esposizione se necessario
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                
                captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                Log.d(TAG, "Capture session restarted with AF_MODE_OFF");
            }
            
            callbackContext.success("Autofocus disabled successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling autofocus", e);
            callbackContext.error("Failed to disable autofocus: " + e.getMessage());
        }
        return true;
    }
    
    /**
     * Metodo alternativo per webcam che non supportano AF_MODE_OFF
     */
    private boolean disableAutofocusAlternative(CallbackContext callbackContext) {
        try {
            autofocusDisabled = true;
            Log.d(TAG, "Using alternative autofocus disable method");
            
            if (captureSession != null && cameraDevice != null && imageReader != null) {
                captureSession.stopRepeating();
                
                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(imageReader.getSurface());
                
                // Prova con AF_MODE_AUTO ma senza trigger
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                
                // Imposta focus fisso se supportato
                Float minFocusDistance = cameraManager.getCameraCharacteristics(externalCameraId)
                        .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                if (minFocusDistance != null && minFocusDistance > 0) {
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
                }
                
                captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                Log.d(TAG, "Alternative autofocus disable applied");
            }
            
            callbackContext.success("Autofocus disabled using alternative method");
        } catch (Exception e) {
            Log.e(TAG, "Error in alternative autofocus disable", e);
            callbackContext.error("Failed to disable autofocus (alternative): " + e.getMessage());
        }
        return true;
    }
    private boolean debugCameraCapabilities(CallbackContext callbackContext) {
        try {
            if (externalCameraId != null) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(externalCameraId);
                
                // Verifica modalità AF disponibili
                int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                StringBuilder afInfo = new StringBuilder("Available AF modes: ");
                if (afModes != null) {
                    for (int mode : afModes) {
                        afInfo.append(mode).append(" ");
                    }
                }
                Log.d(TAG, afInfo.toString());
                
                // Verifica distanza focus
                Float minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                Log.d(TAG, "Min focus distance: " + minFocusDistance);
                
                // Verifica capacità hardware
                Integer hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                Log.d(TAG, "Hardware level: " + hwLevel);
                
                callbackContext.success("Debug info logged");
            } else {
                callbackContext.error("No camera selected");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting camera capabilities", e);
            callbackContext.error("Failed to get camera capabilities: " + e.getMessage());
        }
        return true;
    }
    /**
     * Inizializzazione semplificata che sfrutta il rilevamento nativo di Android
     * Rileva automaticamente webcam USB senza gestione diretta USB
     */
    private boolean initSimple(CallbackContext callbackContext) {
        try {
            cameraManager = (CameraManager) cordova.getActivity().getSystemService(Context.CAMERA_SERVICE);
            
            // Enumera tutte le camere disponibili (incluse USB)
            String[] cameraIds = cameraManager.getCameraIdList();
            Log.d(TAG, "Found " + cameraIds.length + " cameras total");
            
            // Trova automaticamente la prima webcam USB esterna
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                
                // Verifica se è una camera esterna (USB)
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                Integer hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                
                // Le webcam USB sono tipicamente LENS_FACING_EXTERNAL
                if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    externalCameraId = cameraId;
                    Log.d(TAG, "Found USB camera: " + cameraId);
                    
                    // Ottieni informazioni sulla camera
                    JSONObject cameraInfo = getSimpleCameraInfo(characteristics, cameraId);
                    
                    callbackContext.success(cameraInfo);
                    return true;
                }
            }
            
            // Se non trova LENS_FACING_EXTERNAL, cerca camera con caratteristiche USB
            for (String cameraId : cameraIds) {
                if (isLikelyUsbCamera(cameraId)) {
                    externalCameraId = cameraId;
                    Log.d(TAG, "Found likely USB camera: " + cameraId);
                    
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    JSONObject cameraInfo = getSimpleCameraInfo(characteristics, cameraId);
                    
                    callbackContext.success(cameraInfo);
                    return true;
                }
            }
            
            callbackContext.error("No USB camera found");
            
        } catch (Exception e) {
            Log.e(TAG, "Error in initSimple", e);
            callbackContext.error("Failed to initialize: " + e.getMessage());
        }
        return true;
    }

    /**
     * Verifica se una camera è probabilmente USB basandosi sull'ID
     */
    private boolean isLikelyUsbCamera(String cameraId) {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            
            // Le webcam USB hanno spesso:
            // - Hardware level LIMITED
            // - Pochi formati supportati
            // - ID numerici alti (2, 3, 4...)
            
            Integer hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            if (hwLevel != null && hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) {
                
                // Verifica se l'ID è numerico e > 1 (tipico delle USB)
                try {
                    int id = Integer.parseInt(cameraId);
                    if (id >= 2) {
                        Log.d(TAG, "Camera " + cameraId + " likely USB (ID >= 2, LIMITED level)");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    // ID non numerico, potrebbe essere USB con nome specifico
                    Log.d(TAG, "Camera " + cameraId + " has non-numeric ID, checking other criteria");
                }
            }
            
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking if camera is USB: " + cameraId, e);
            return false;
        }
    }

    /**
     * Ottieni informazioni essenziali sulla camera
     */
    private JSONObject getSimpleCameraInfo(CameraCharacteristics characteristics, String cameraId) {
        JSONObject info = new JSONObject();
        try {
            info.put("cameraId", cameraId);
            
            // Facing
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            String facing = "unknown";
            if (lensFacing != null) {
                switch (lensFacing) {
                    case CameraCharacteristics.LENS_FACING_FRONT:
                        facing = "front";
                        break;
                    case CameraCharacteristics.LENS_FACING_BACK:
                        facing = "back";
                        break;
                    case CameraCharacteristics.LENS_FACING_EXTERNAL:
                        facing = "external";
                        break;
                }
            }
            info.put("facing", facing);
            
            // Hardware level
            Integer hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            String level = "unknown";
            if (hwLevel != null) {
                switch (hwLevel) {
                    case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                        level = "limited";
                        break;
                    case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                        level = "full";
                        break;
                    case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                        level = "legacy";
                        break;
                }
            }
            info.put("hardwareLevel", level);
            
            // Modalità autofocus supportate
            int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            JSONArray afArray = new JSONArray();
            if (afModes != null) {
                for (int mode : afModes) {
                    afArray.put(mode);
                }
            }
            info.put("autofocusModes", afArray);
            
            // Risoluzione massima
            StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configMap != null) {
                Size[] sizes = configMap.getOutputSizes(ImageFormat.JPEG);
                if (sizes != null && sizes.length > 0) {
                    Size maxSize = sizes[0]; // Prima è solitamente la più grande
                    info.put("maxWidth", maxSize.getWidth());
                    info.put("maxHeight", maxSize.getHeight());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting camera info", e);
        }
        return info;
    }

    /**
     * Trigger manuale dell'autofocus per webcam USB
     */
    private boolean triggerAutofocus(CallbackContext callbackContext) {
        try {
            if (captureSession != null && cameraDevice != null) {
                Log.d(TAG, "Triggering manual autofocus");
                
                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(imageReader.getSurface());
                
                // Imposta modalità autofocus ottimale per webcam USB
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                
                // Cattura singola per trigger AF
                captureSession.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                  @NonNull CaptureRequest request,
                                                  @NonNull TotalCaptureResult result) {
                        Log.d(TAG, "Autofocus trigger completed");
                        
                        // Dopo il trigger, torna alla modalità normale
                        try {
                            CaptureRequest.Builder normalBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            normalBuilder.addTarget(imageReader.getSurface());
                            normalBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                            normalBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                            
                            captureSession.setRepeatingRequest(normalBuilder.build(), null, backgroundHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Error setting normal AF mode", e);
                        }
                    }
                }, backgroundHandler);
                
                callbackContext.success("Autofocus triggered");
            } else {
                callbackContext.error("Camera not ready");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error triggering autofocus", e);
            callbackContext.error("Failed to trigger autofocus: " + e.getMessage());
        }
        return true;
    }

    /**
     * Imposta autofocus ottimizzato per webcam USB
     */
    private boolean optimizeAutofocusForUsb(CallbackContext callbackContext) {
        try {
            if (captureSession != null && cameraDevice != null && imageReader != null) {
                Log.d(TAG, "Optimizing autofocus for USB webcam");
                captureSession.stopRepeating();
                
                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(imageReader.getSurface());
                
                // Configurazione ottimizzata per webcam USB
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                
                // Stabilizza altri parametri che possono interferire con AF
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                
                // Riduce la velocità di aggiornamento per dare tempo all'AF
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new android.util.Range<>(15, 30));
                
                captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                Log.d(TAG, "USB webcam autofocus optimization applied");
                
                callbackContext.success("Autofocus optimized for USB webcam");
            } else {
                callbackContext.error("Camera not ready");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error optimizing autofocus", e);
            callbackContext.error("Failed to optimize autofocus: " + e.getMessage());
        }
        return true;
    }

    /**
     * Imposta manualmente la distanza di focus
     */
    private boolean setFocusDistance(JSONArray args, CallbackContext callbackContext) {
        try {
            float focusDistance = (float) args.getDouble(0); // Valore tra 0.0 (infinito) e 1.0 (minimo)
            
            if (focusDistance < 0.0f || focusDistance > 1.0f) {
                callbackContext.error("Focus distance must be between 0.0 (infinity) and 1.0 (minimum distance)");
                return true;
            }
            
            if (cameraDevice == null || captureSession == null) {
                callbackContext.error("Camera not opened or session not active");
                return true;
            }
            
            try {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(externalCameraId);
                Float minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                
                if (minFocusDistance == null || minFocusDistance == 0.0f) {
                    callbackContext.error("Camera does not support manual focus control");
                    return true;
                }
                
                // Converti il valore normalizzato (0-1) alla distanza reale
                float actualDistance = focusDistance * minFocusDistance;
                
                CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                requestBuilder.addTarget(imageReader.getSurface());
                
                // Imposta la modalità di messa a fuoco manuale
                requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, actualDistance);
                
                // Applica le impostazioni alla sessione
                captureSession.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler);
                
                Log.d(TAG, "Focus distance set to: " + actualDistance + " (normalized: " + focusDistance + ")");
                callbackContext.success("Focus distance set successfully");
                
            } catch (CameraAccessException e) {
                Log.e(TAG, "Error setting focus distance", e);
                callbackContext.error("Failed to set focus distance: " + e.getMessage());
            }
            
        } catch (JSONException e) {
            callbackContext.error("Invalid focus distance parameter");
        }
        
        return true;
    }

    private boolean setUvcAutoFocus(JSONArray args, CallbackContext callbackContext) {
        try {
            if (args.length() == 0 || args.isNull(0)) {
                callbackContext.error("Missing enable parameter");
                return true;
            }
            
            boolean enable;
            try {
                enable = args.getBoolean(0);
            } catch (JSONException e) {
                callbackContext.error("Enable parameter must be a boolean (true or false)");
                return true;
            }
            
            if (!initUvcConnection()) {
                callbackContext.error("Failed to initialize UVC connection");
                return true;
            }
            
            // Sospendi la sessione Camera2 per evitare conflitti con il driver UVC
            suspendCameraForUvc();
            try {
                // Usa il Camera Terminal corretto
                int wIndex = getWIndexForCameraTerminal();
                byte[] data = new byte[1];
                data[0] = (byte) (enable ? 1 : 0);
                int result = uvcConnection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                    0x01, // SET_CUR
                    0x0800, // CT_FOCUS_AUTO_CONTROL (1 byte)
                    wIndex,
                    data,
                    data.length,
                    1000
                );

                if (result >= 0) {
                    Log.d(TAG, "UVC AutoFocus set (CT)=" + enable + ", wIndex=0x" + Integer.toHexString(wIndex));
                    callbackContext.success("AutoFocus " + (enable ? "enabled" : "disabled"));
                } else {
                    // Fallback 1: prova wIndex invertito
                    int altWIndex = getWIndexForCameraTerminalSwapped();
                    int result2 = uvcConnection.controlTransfer(
                        UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                        0x01,
                        0x0800,
                        altWIndex,
                        data,
                        data.length,
                        1000
                    );
                    if (result2 >= 0) {
                        Log.d(TAG, "UVC AutoFocus set using swapped wIndex=0x" + Integer.toHexString(altWIndex));
                        callbackContext.success("AutoFocus " + (enable ? "enabled" : "disabled"));
                    } else {
                        // Fallback 2: prova via Processing Unit se esiste (alcune cam instradano focus auto lì)
                        if (processingUnitId > 0) {
                            int wIndexPU = ((processingUnitId & 0xFF) << 8) | (vcInterfaceNumber & 0xFF);
                            int result3 = uvcConnection.controlTransfer(
                                UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                                0x01,
                                0x0800,
                                wIndexPU,
                                data,
                                data.length,
                                1000
                            );
                            if (result3 >= 0) {
                                Log.d(TAG, "UVC AutoFocus set via ProcessingUnit wIndex=0x" + Integer.toHexString(wIndexPU));
                                callbackContext.success("AutoFocus " + (enable ? "enabled" : "disabled"));
                            } else {
                                callbackContext.error("Failed to set AutoFocus: primary=" + result + ", swapped=" + result2 + ", PU=" + result3);
                            }
                        } else {
                            callbackContext.error("Failed to set AutoFocus: primary=" + result + ", swapped=" + result2);
                        }
                    }
                }
            } finally {
                // Rilascia subito la connessione UVC per non bloccare l'external camera provider
                releaseUvcConnection();
                resumeCameraAfterUvc();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error setting UVC AutoFocus", e);
            callbackContext.error("Error: " + e.getMessage());
        }
        
        return true;
    }

    private boolean setUvcFocusAbsolute(JSONArray args, CallbackContext callbackContext) {
        try {
            double normalizedValue = args.getDouble(0); // 0.0 - 1.0
            
            if (normalizedValue < 0.0 || normalizedValue > 1.0) {
                callbackContext.error("Focus value must be between 0.0 and 1.0");
                return true;
            }
            
            if (!initUvcConnection()) {
                callbackContext.error("Failed to initialize UVC connection");
                return true;
            }
            
            // Sospendi la sessione Camera2 durante i control transfer
            suspendCameraForUvc();
            int wIndex = getWIndexForCameraTerminal();
            
            // Disabilita prima l'autofocus (CT_FOCUS_AUTO_CONTROL)
            byte[] autoFocusData = new byte[1];
            autoFocusData[0] = 0;
            uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x01, // SET_CUR
                0x0800, // CT_FOCUS_AUTO_CONTROL
                wIndex,
                autoFocusData,
                autoFocusData.length,
                1000
            );
            
            // Calcola il valore assoluto
            int absoluteValue = (int) (focusAbsoluteMin + (normalizedValue * (focusAbsoluteMax - focusAbsoluteMin)));
            
            byte[] data = new byte[2];
            data[0] = (byte) (absoluteValue & 0xFF);
            data[1] = (byte) ((absoluteValue >> 8) & 0xFF);
            
            int result = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x01, // SET_CUR
                0x0600, // CT_FOCUS_ABSOLUTE_CONTROL (2 bytes, LE)
                wIndex,
                data,
                data.length,
                1000
            );
            
            if (result >= 0) {
                Log.d(TAG, "UVC Focus Absolute set to: " + absoluteValue + " (normalized: " + normalizedValue + ")");
                callbackContext.success("Focus set to " + absoluteValue);
            } else {
                // Fallback 1: prova wIndex invertito
                int altWIndex = getWIndexForCameraTerminalSwapped();
                int result2 = uvcConnection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                    0x01,
                    0x0600,
                    altWIndex,
                    data,
                    data.length,
                    1000
                );
                if (result2 >= 0) {
                    Log.d(TAG, "UVC Focus Absolute set using swapped wIndex to: " + absoluteValue);
                    callbackContext.success("Focus set to " + absoluteValue);
                } else {
                    // Fallback 2: prova via Processing Unit se esiste
                    if (processingUnitId > 0) {
                        int wIndexPU = ((processingUnitId & 0xFF) << 8) | (vcInterfaceNumber & 0xFF);
                        int result3 = uvcConnection.controlTransfer(
                            UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                            0x01,
                            0x0600,
                            wIndexPU,
                            data,
                            data.length,
                            1000
                        );
                        if (result3 >= 0) {
                            Log.d(TAG, "UVC Focus Absolute set via ProcessingUnit to: " + absoluteValue);
                            callbackContext.success("Focus set to " + absoluteValue);
                        } else {
                            callbackContext.error("Failed to set Focus Absolute: primary=" + result + ", swapped=" + result2 + ", PU=" + result3);
                        }
                    } else {
                        callbackContext.error("Failed to set Focus Absolute: primary=" + result + ", swapped=" + result2);
                    }
                }
            }
            
            // Rilascia subito la connessione UVC per non bloccare l'enumerazione e ripristina la sessione Camera2
            releaseUvcConnection();
            resumeCameraAfterUvc();
            
        } catch (Exception e) {
            Log.e(TAG, "Error setting UVC Focus Absolute", e);
            callbackContext.error("Error: " + e.getMessage());
        }
        
        return true;
    }

    private boolean setUvcAutoExposure(JSONArray args, CallbackContext callbackContext) {
        try {
            if (args.length() == 0 || args.isNull(0)) {
                callbackContext.error("Missing enable parameter");
                return true;
            }
            boolean enable = args.getBoolean(0);
            if (!initUvcConnection()) {
                callbackContext.error("Failed to initialize UVC connection");
                return true;
            }
            suspendCameraForUvc();
            try {
                int wIndexCT = getWIndexForCameraTerminal();
                byte[] data = new byte[1];
                // CT_AE_MODE_CONTROL: 0x01 Manual, 0x02 Auto, 0x04 Shutter, 0x08 Aperture
                data[0] = (byte) (enable ? 0x02 : 0x01);
                int result = uvcConnection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                    0x01, // SET_CUR
                    0x0200, // CT_AE_MODE_CONTROL
                    wIndexCT,
                    data,
                    data.length,
                    1000
                );
                if (result < 0) {
                    // Fallback: wIndex swapped
                    int alt = getWIndexForCameraTerminalSwapped();
                    result = uvcConnection.controlTransfer(
                        UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                        0x01,
                        0x0200,
                        alt,
                        data,
                        data.length,
                        1000
                    );
                }
                if (!enable && result >= 0) {
                    // Portiamo AE Priority a 0 (preferisci frame rate) per assicurare controllo manuale
                    byte[] pr = new byte[]{0x00};
                    uvcConnection.controlTransfer(
                        UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                        0x01,
                        0x0300, // CT_AE_PRIORITY_CONTROL
                        wIndexCT,
                        pr,
                        pr.length,
                        1000
                    );
                }
                if (result >= 0) {
                    callbackContext.success("AutoExposure " + (enable ? "enabled" : "disabled"));
                } else {
                    callbackContext.error("Failed to set AutoExposure: " + result);
                }
            } finally {
                releaseUvcConnection();
                resumeCameraAfterUvc();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting UVC Auto Exposure", e);
            callbackContext.error("Error: " + e.getMessage());
        }
        return true;
    }

    private boolean setUvcExposureAbsolute(JSONArray args, CallbackContext callbackContext) {
        try {
            double normalized = args.getDouble(0); // 0..1
            if (normalized < 0.0 || normalized > 1.0) {
                callbackContext.error("Exposure value must be between 0.0 and 1.0");
                return true;
            }
            if (!initUvcConnection()) {
                callbackContext.error("Failed to initialize UVC connection");
                return true;
            }
            suspendCameraForUvc();
            try {
                int wIndex = getWIndexForCameraTerminal();
                // Disabilita Auto Exposure prima (CT_AE_MODE_CONTROL -> Manual)
                byte[] aem = new byte[]{0x01}; // Manual
                uvcConnection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                    0x01,
                    0x0200, // CT_AE_MODE_CONTROL
                    wIndex,
                    aem,
                    aem.length,
                    1000
                );
                // Imposta AE Priority a 0 (opzionale ma utile)
                byte[] pr = new byte[]{0x00};
                uvcConnection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                    0x01,
                    0x0300, // CT_AE_PRIORITY_CONTROL
                    wIndex,
                    pr,
                    pr.length,
                    1000
                );

                // Calcola valore assoluto nell'intervallo letto
                if (exposureAbsoluteMax <= exposureAbsoluteMin) {
                    readExposureAbsoluteRange();
                }
                int absVal;
                if (exposureAbsoluteMax > exposureAbsoluteMin) {
                    absVal = (int) (exposureAbsoluteMin + normalized * (exposureAbsoluteMax - exposureAbsoluteMin));
                } else {
                    // Fallback a un range tipico (1..10000 unità da 100µs)
                    exposureAbsoluteMin = 1;
                    exposureAbsoluteMax = 10000;
                    absVal = (int) (exposureAbsoluteMin + normalized * (exposureAbsoluteMax - exposureAbsoluteMin));
                }

                byte[] data = new byte[4];
                data[0] = (byte) (absVal & 0xFF);
                data[1] = (byte) ((absVal >> 8) & 0xFF);
                data[2] = (byte) ((absVal >> 16) & 0xFF);
                data[3] = (byte) ((absVal >> 24) & 0xFF);

                int result = uvcConnection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                    0x01, // SET_CUR
                    0x0400, // CT_EXPOSURE_TIME_ABSOLUTE_CONTROL
                    wIndex,
                    data,
                    data.length,
                    1000
                );
                if (result >= 0) {
                    callbackContext.success("Exposure set to " + absVal);
                } else {
                    // Fallback: prova wIndex swapped
                    int altWIndex = getWIndexForCameraTerminalSwapped();
                    int result2 = uvcConnection.controlTransfer(
                        UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                        0x01,
                        0x0400,
                        altWIndex,
                        data,
                        data.length,
                        1000
                    );
                    if (result2 >= 0) {
                        callbackContext.success("Exposure set to " + absVal);
                    } else {
                        callbackContext.error("Failed to set Exposure: primary=" + result + ", swapped=" + result2);
                    }
                }
            } finally {
                releaseUvcConnection();
                resumeCameraAfterUvc();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting UVC Exposure Absolute", e);
            callbackContext.error("Error: " + e.getMessage());
        }
        return true;
    }

    private boolean debugUvcExposure(CallbackContext callbackContext) {
        if (!initUvcConnection()) {
            callbackContext.error("Failed to initialize UVC connection");
            return true;
        }
        suspendCameraForUvc();
        StringBuilder dbg = new StringBuilder();
        try {
            int wIndex = getWIndexForCameraTerminal();
            byte[] info = new byte[1];
            int rInfo = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x86,
                0x0400,
                wIndex,
                info,
                info.length,
                1000
            );
            dbg.append("CT_EXPOSURE GET_INFO: ").append(rInfo >= 0 ? (info[0] & 0xFF) : rInfo).append("\n");
            byte[] cur = new byte[4];
            int rCur = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x81,
                0x0400,
                wIndex,
                cur,
                cur.length,
                1000
            );
            int curVal = (rCur >= 0) ? ((cur[3] & 0xFF) << 24 | (cur[2] & 0xFF) << 16 | (cur[1] & 0xFF) << 8 | (cur[0] & 0xFF)) : rCur;
            dbg.append("CT_EXPOSURE GET_CUR: ").append(curVal).append("\n");
        } catch (Exception e) {
            dbg.append("EXC: ").append(e.getMessage());
        }
        Log.d(TAG, dbg.toString());
        callbackContext.success(dbg.toString());
        releaseUvcConnection();
        resumeCameraAfterUvc();
        return true;
    }

    private boolean setUvcBrightness(JSONArray args, CallbackContext callbackContext) {
        try {
            double normalized = args.getDouble(0); // 0..1
            if (normalized < 0.0 || normalized > 1.0) {
                callbackContext.error("Brightness value must be between 0.0 and 1.0");
                return true;
            }
            if (!initUvcConnection()) {
                callbackContext.error("Failed to initialize UVC connection");
                return true;
            }
            suspendCameraForUvc();
            try {
                if (processingUnitId <= 0) {
                    callbackContext.error("Processing Unit not found for Brightness control");
                    return true;
                }
                int wIndexPU = ((processingUnitId & 0xFF) << 8) | (vcInterfaceNumber & 0xFF);
                if (brightnessMax <= brightnessMin) {
                    readBrightnessRange();
                }
                int abs;
                if (brightnessMax > brightnessMin) {
                    abs = (int) (brightnessMin + normalized * (brightnessMax - brightnessMin));
                } else {
                    // Fallback tipico: 0..255
                    brightnessMin = 0;
                    brightnessMax = 255;
                    abs = (int) (brightnessMin + normalized * (brightnessMax - brightnessMin));
                }
                byte[] data = new byte[2];
                data[0] = (byte) (abs & 0xFF);
                data[1] = (byte) ((abs >> 8) & 0xFF);
                int result = uvcConnection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | 0x01,
                    0x01,
                    0x0200, // PU_BRIGHTNESS_CONTROL
                    wIndexPU,
                    data,
                    data.length,
                    1000
                );
                if (result >= 0) {
                    callbackContext.success("Brightness set to " + abs);
                } else {
                    callbackContext.error("Failed to set Brightness: " + result);
                }
            } finally {
                releaseUvcConnection();
                resumeCameraAfterUvc();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting UVC Brightness", e);
            callbackContext.error("Error: " + e.getMessage());
        }
        return true;
    }

    private boolean debugUvcBrightness(CallbackContext callbackContext) {
        if (!initUvcConnection()) {
            callbackContext.error("Failed to initialize UVC connection");
            return true;
        }
        suspendCameraForUvc();
        StringBuilder dbg = new StringBuilder();
        try {
            if (processingUnitId > 0) {
                int wIndexPU = ((processingUnitId & 0xFF) << 8) | (vcInterfaceNumber & 0xFF);
                byte[] cur = new byte[2];
                int r = uvcConnection.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                    0x81,
                    0x0200,
                    wIndexPU,
                    cur,
                    cur.length,
                    1000
                );
                dbg.append("PU_BRIGHTNESS_CONTROL GET_CUR: ")
                   .append(r >= 0 ? ((cur[1] << 8) | (cur[0] & 0xFF)) : r).append("\n");
            }
        } catch (Exception e) {
            dbg.append("EXC: ").append(e.getMessage());
        }
        Log.d(TAG, dbg.toString());
        callbackContext.success(dbg.toString());
        releaseUvcConnection();
        resumeCameraAfterUvc();
        return true;
    }

    private boolean recoverCamera(CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                // Rilascia qualsiasi connessione UVC pendente
                releaseUvcConnection();
                // Chiudi sessione e device se presenti
                try {
                    if (captureSession != null) {
                        try { captureSession.stopRepeating(); } catch (Exception ignored) {}
                        try { captureSession.close(); } catch (Exception ignored) {}
                        captureSession = null;
                    }
                    if (cameraDevice != null) {
                        try { cameraDevice.close(); } catch (Exception ignored) {}
                        cameraDevice = null;
                    }
                } catch (Exception ignored) {}
                isPreviewActive = false;
                // Attendi che il provider rilasci la webcam
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                // Assicurati che il CameraManager e il background thread esistano
                if (cameraManager == null) {
                    cameraManager = (CameraManager) cordova.getActivity().getSystemService(Context.CAMERA_SERVICE);
                }
                if (backgroundThread == null || backgroundHandler == null) {
                    startBackgroundThread();
                }
                // Se non abbiamo più un ID, riesegui discovery; altrimenti riapri direttamente
                if (externalCameraId == null) {
                    initializeCamera();
                } else {
                    openCameraDevice();
                }
                callbackContext.success("Camera recovery attempted");
            } catch (Exception e) {
                Log.e(TAG, "Error recovering camera", e);
                callbackContext.error("Failed to recover camera: " + e.getMessage());
            }
        });
        return true;
    }

    private boolean initUvcConnection() {
        if (uvcConnection != null && videoControlInterface != null) {
            return true;
        }
        
        try {
            UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            
            UsbDevice logitechDevice = null;
            for (UsbDevice device : deviceList.values()) {
                if (device.getVendorId() == 0x046d) { // Logitech
                    logitechDevice = device;
                    break;
                }
            }
            
            if (logitechDevice == null) {
                Log.e(TAG, "Logitech device not found");
                return false;
            }
            
            // Trova l'interfaccia VideoControl (classe 14, sottoclasse 1)
            for (int i = 0; i < logitechDevice.getInterfaceCount(); i++) {
                UsbInterface intf = logitechDevice.getInterface(i);
                if (intf.getInterfaceClass() == 14 && intf.getInterfaceSubclass() == 1) {
                    videoControlInterface = intf;
                    break;
                }
            }
            
            if (videoControlInterface == null) {
                Log.e(TAG, "VideoControl interface not found");
                return false;
            }
            
            uvcConnection = usbManager.openDevice(logitechDevice);
            if (uvcConnection == null) {
                Log.e(TAG, "Failed to open USB device");
                return false;
            }
            
            // Claim veloce dell'interfaccia VC per garantire controllo stabile; rilasceremo immediatamente dopo i transfer
            try {
                uvcConnection.claimInterface(videoControlInterface, true);
                // Se supportato, assicurati dell'altsetting 0
                try { uvcConnection.setInterface(videoControlInterface); } catch (Exception ignored) {}
            } catch (Exception e) {
                Log.w(TAG, "claimInterface VC failed", e);
            }
            
            // Salva device e bInterfaceNumber
            uvcDevice = logitechDevice;
            vcInterfaceNumber = videoControlInterface.getId();
            
            // Scopri gli entity ID reali dal descriptor
            discoverUvcEntities();
            
            // Leggi il range del Focus Absolute
            readFocusAbsoluteRange();
            // Leggi il range dell'Exposure Absolute
            readExposureAbsoluteRange();
            // Leggi il range Brightness
            readBrightnessRange();
            
            Log.d(TAG, "UVC connection initialized successfully");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing UVC connection", e);
            return false;
        }
    }

    private void readFocusAbsoluteRange() {
        try {
            int wIndex = getWIndexForCameraTerminal();
            // GET_MIN
            byte[] minData = new byte[2];
            int result = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x82, // GET_MIN
                0x0600, // CT_FOCUS_ABSOLUTE_CONTROL
                wIndex,
                minData,
                minData.length,
                1000
            );
            
            if (result >= 0) {
                focusAbsoluteMin = (minData[1] << 8) | (minData[0] & 0xFF);
            }
            
            // GET_MAX
            byte[] maxData = new byte[2];
            result = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x83, // GET_MAX
                0x0600, // CT_FOCUS_ABSOLUTE_CONTROL
                wIndex,
                maxData,
                maxData.length,
                1000
            );
            
            if (result >= 0) {
                focusAbsoluteMax = (maxData[1] << 8) | (maxData[0] & 0xFF);
            }
            
            Log.d(TAG, "Focus Absolute range: " + focusAbsoluteMin + " - " + focusAbsoluteMax);
            
        } catch (Exception e) {
            Log.e(TAG, "Error reading focus range", e);
            focusAbsoluteMin = 0;
            focusAbsoluteMax = 255;
        }
    }

    private void readExposureAbsoluteRange() {
        try {
            int wIndex = getWIndexForCameraTerminal();
            // Exposure Absolute is 4 bytes per UVC 1.5 (milliseconds in 100µs units), many cams accept 2 bytes; leggere 4
            byte[] minData = new byte[4];
            int rmin = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x82, // GET_MIN
                0x0400, // CT_EXPOSURE_TIME_ABSOLUTE_CONTROL
                wIndex,
                minData,
                minData.length,
                1000
            );
            byte[] maxData = new byte[4];
            int rmax = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x83, // GET_MAX
                0x0400,
                wIndex,
                maxData,
                maxData.length,
                1000
            );
            if (rmin >= 0 && rmax >= 0) {
                exposureAbsoluteMin = (minData[3] & 0xFF) << 24 | (minData[2] & 0xFF) << 16 | (minData[1] & 0xFF) << 8 | (minData[0] & 0xFF);
                exposureAbsoluteMax = (maxData[3] & 0xFF) << 24 | (maxData[2] & 0xFF) << 16 | (maxData[1] & 0xFF) << 8 | (maxData[0] & 0xFF);
                Log.d(TAG, "Exposure Absolute range: " + exposureAbsoluteMin + " - " + exposureAbsoluteMax);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading exposure range", e);
            exposureAbsoluteMin = 0;
            exposureAbsoluteMax = 0;
        }
    }

    private void readBrightnessRange() {
        try {
            // Brightness è PU_BRIGHTNESS_CONTROL = 0x0200 sul Processing Unit
            if (processingUnitId <= 0) return;
            int wIndexPU = ((processingUnitId & 0xFF) << 8) | (vcInterfaceNumber & 0xFF);
            byte[] min = new byte[2];
            int rmin = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x82, // GET_MIN
                0x0200,
                wIndexPU,
                min,
                min.length,
                1000
            );
            byte[] max = new byte[2];
            int rmax = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x83, // GET_MAX
                0x0200,
                wIndexPU,
                max,
                max.length,
                1000
            );
            if (rmin >= 0 && rmax >= 0) {
                brightnessMin = (min[1] << 8) | (min[0] & 0xFF);
                brightnessMax = (max[1] << 8) | (max[0] & 0xFF);
                Log.d(TAG, "Brightness range: " + brightnessMin + " - " + brightnessMax);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading brightness range", e);
            brightnessMin = 0;
            brightnessMax = 0;
        }
    }

    private boolean debugUvcControls(CallbackContext callbackContext) {
        if (!initUvcConnection()) {
            callbackContext.error("Failed to initialize UVC connection");
            return true;
        }
        // Sospendi eventuale preview per evitare conflitti
        suspendCameraForUvc();
        
        StringBuilder debug = new StringBuilder();
        debug.append("UVC Debug Info:\n");
        debug.append("Interface (VC) number: ").append(vcInterfaceNumber).append("\n");
        debug.append("CameraTerminalId: ").append(cameraTerminalId).append(" ProcessingUnitId: ").append(processingUnitId).append("\n");
        
        int wIndex = getWIndexForCameraTerminal();
        // GET_INFO for CT_FOCUS_AUTO_CONTROL
        try {
            byte[] info = new byte[1];
            int r = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x86, // GET_INFO
                0x0800,
                wIndex,
                info,
                info.length,
                1000
            );
            debug.append("CT_FOCUS_AUTO_CONTROL GET_INFO: ").append(r >= 0 ? (info[0] & 0xFF) : r).append("\n");
        } catch (Exception e) {
            debug.append("CT_FOCUS_AUTO_CONTROL GET_INFO EXC: ").append(e.getMessage()).append("\n");
        }
        // GET_CUR for CT_FOCUS_AUTO_CONTROL
        try {
            byte[] cur = new byte[1];
            int r = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x81, // GET_CUR
                0x0800,
                wIndex,
                cur,
                cur.length,
                1000
            );
            debug.append("CT_FOCUS_AUTO_CONTROL GET_CUR: ").append(r >= 0 ? (cur[0] & 0xFF) : r).append("\n");
        } catch (Exception e) {
            debug.append("CT_FOCUS_AUTO_CONTROL GET_CUR EXC: ").append(e.getMessage()).append("\n");
        }
        // MIN/MAX for CT_FOCUS_ABSOLUTE_CONTROL
        try {
            byte[] min = new byte[2];
            int rmin = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x82, // GET_MIN
                0x0600,
                wIndex,
                min,
                min.length,
                1000
            );
            byte[] max = new byte[2];
            int rmax = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x83, // GET_MAX
                0x0600,
                wIndex,
                max,
                max.length,
                1000
            );
            debug.append("CT_FOCUS_ABSOLUTE_CONTROL MIN/MAX: ")
                 .append(rmin >= 0 ? ((min[1] << 8) | (min[0] & 0xFF)) : rmin)
                 .append("/")
                 .append(rmax >= 0 ? ((max[1] << 8) | (max[0] & 0xFF)) : rmax)
                 .append("\n");
        } catch (Exception e) {
            debug.append("CT_FOCUS_ABSOLUTE_CONTROL EXC: ").append(e.getMessage()).append("\n");
        }
        // MIN/MAX for CT_EXPOSURE_TIME_ABSOLUTE_CONTROL
        try {
            byte[] min = new byte[4];
            int rmin = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x82, // GET_MIN
                0x0400,
                wIndex,
                min,
                min.length,
                1000
            );
            byte[] max = new byte[4];
            int rmax = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                0x83, // GET_MAX
                0x0400,
                wIndex,
                max,
                max.length,
                1000
            );
            int minVal = (rmin >= 0) ? ((min[3] & 0xFF) << 24 | (min[2] & 0xFF) << 16 | (min[1] & 0xFF) << 8 | (min[0] & 0xFF)) : rmin;
            int maxVal = (rmax >= 0) ? ((max[3] & 0xFF) << 24 | (max[2] & 0xFF) << 16 | (max[1] & 0xFF) << 8 | (max[0] & 0xFF)) : rmax;
            debug.append("CT_EXPOSURE_TIME_ABSOLUTE_CONTROL MIN/MAX: ").append(minVal).append("/").append(maxVal).append("\n");
        } catch (Exception e) {
            debug.append("CT_EXPOSURE_TIME_ABSOLUTE_CONTROL EXC: ").append(e.getMessage()).append("\n");
        }
        // MIN/MAX for PU_BRIGHTNESS_CONTROL
        try {
            if (processingUnitId > 0) {
                int wIndexPU = ((processingUnitId & 0xFF) << 8) | (vcInterfaceNumber & 0xFF);
                byte[] min = new byte[2];
                int rmin = uvcConnection.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                    0x82, // GET_MIN
                    0x0200,
                    wIndexPU,
                    min,
                    min.length,
                    1000
                );
                byte[] max = new byte[2];
                int rmax = uvcConnection.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | 0x01,
                    0x83, // GET_MAX
                    0x0200,
                    wIndexPU,
                    max,
                    max.length,
                    1000
                );
                debug.append("PU_BRIGHTNESS_CONTROL MIN/MAX: ")
                     .append(rmin >= 0 ? ((min[1] << 8) | (min[0] & 0xFF)) : rmin)
                     .append("/")
                     .append(rmax >= 0 ? ((max[1] << 8) | (max[0] & 0xFF)) : rmax)
                     .append("\n");
            }
        } catch (Exception e) {
            debug.append("PU_BRIGHTNESS_CONTROL EXC: ").append(e.getMessage()).append("\n");
        }
        
        Log.d(TAG, debug.toString());
        callbackContext.success(debug.toString());
        
        // Rilascia subito la connessione UVC e ripristina la preview
        releaseUvcConnection();
        resumeCameraAfterUvc();
        return true;
    }

    // Helper: compone wIndex = (bEntityID << 8) | bInterfaceNumber (per UVC spec)
    private int getWIndexForCameraTerminal() {
        int iface = vcInterfaceNumber >= 0 ? vcInterfaceNumber : (videoControlInterface != null ? videoControlInterface.getId() : 0);
        int term = cameraTerminalId > 0 ? cameraTerminalId : 0x01;
        return ((term & 0xFF) << 8) | (iface & 0xFF);
    }

    // Fallback: compone wIndex come (bInterfaceNumber << 8) | bEntityID
    private int getWIndexForCameraTerminalSwapped() {
        int iface = vcInterfaceNumber >= 0 ? vcInterfaceNumber : (videoControlInterface != null ? videoControlInterface.getId() : 0);
        int term = cameraTerminalId > 0 ? cameraTerminalId : 0x01;
        return ((iface & 0xFF) << 8) | (term & 0xFF);
    }

    // Sospende la sessione Camera2 per i control transfer UVC
    private void suspendCameraForUvc() {
        try {
            if (captureSession != null) {
                try { captureSession.stopRepeating(); } catch (Exception ignored) {}
                try { captureSession.close(); } catch (Exception ignored) {}
                captureSession = null;
            }
            if (cameraDevice != null) {
                try { cameraDevice.close(); } catch (Exception ignored) {}
                cameraDevice = null;
            }
        } catch (Exception ignored) {}
    }

    // Ripristina la preview con le impostazioni correnti
    private void resumeCameraAfterUvc() {
        try {
            // Riapri la camera e ricrea la preview
            if (cameraManager != null && externalCameraId != null) {
                if (backgroundHandler != null) {
                    backgroundHandler.postDelayed(() -> {
                        try { openCameraDevice(); } catch (Exception e) { Log.w(TAG, "openCameraDevice (delayed) failed", e); }
                    }, 300);
                } else {
                    openCameraDevice();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resume preview after UVC control", e);
        }
    }

    // Legge e interpreta i descriptor per ottenere bInterfaceNumber e entity ID corretti
    private void discoverUvcEntities() {
        try {
            if (uvcConnection == null || uvcDevice == null || videoControlInterface == null) return;

            // Primo step: leggi i primi 9 byte per wTotalLength
            byte[] cfg9 = new byte[9];
            int n = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_STANDARD | 0x00,
                0x06, // GET_DESCRIPTOR
                (0x02 << 8) | 0, // CONFIGURATION descriptor, index 0
                0,
                cfg9,
                cfg9.length,
                1000
            );
            if (n < 9) return;
            int totalLength = (cfg9[3] & 0xFF) << 8 | (cfg9[2] & 0xFF);
            if (totalLength < 9) return;

            byte[] cfg = new byte[Math.min(1024, Math.max(totalLength, 256))];
            n = uvcConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_STANDARD | 0x00,
                0x06,
                (0x02 << 8) | 0,
                0,
                cfg,
                cfg.length,
                1000
            );
            if (n <= 0) return;

            int offset = 0;
            int currentInterface = -1;
            int currentClass = -1;
            int currentSubclass = -1;
            boolean inVideoControl = false;

            while (offset + 2 <= n) {
                int bLength = cfg[offset] & 0xFF;
                if (bLength == 0 || offset + bLength > n) break;
                int bDescriptorType = cfg[offset + 1] & 0xFF;

                if (bDescriptorType == 0x04 && bLength >= 9) { // INTERFACE
                    currentInterface = cfg[offset + 2] & 0xFF; // bInterfaceNumber
                    currentClass = cfg[offset + 5] & 0xFF;      // bInterfaceClass
                    currentSubclass = cfg[offset + 6] & 0xFF;   // bInterfaceSubClass
                    inVideoControl = (currentClass == 14 && currentSubclass == 1);
                    if (inVideoControl) {
                        vcInterfaceNumber = currentInterface;
                    }
                } else if (bDescriptorType == 0x24 && bLength >= 3 && inVideoControl) { // CS_INTERFACE
                    int subType = cfg[offset + 2] & 0xFF;
                    if (subType == 0x02 && bLength >= 8) { // VC_INPUT_TERMINAL
                        int bTerminalID = cfg[offset + 3] & 0xFF;
                        int wTerminalType = (cfg[offset + 5] & 0xFF) << 8 | (cfg[offset + 4] & 0xFF);
                        if (wTerminalType == 0x0201) { // ITT_CAMERA
                            cameraTerminalId = bTerminalID;
                        }
                    } else if (subType == 0x05 && bLength >= 6) { // VC_PROCESSING_UNIT
                        int bUnitID = cfg[offset + 3] & 0xFF;
                        processingUnitId = bUnitID;
                    }
                }

                offset += bLength;
            }

            Log.d(TAG, "UVC entities: iface=" + vcInterfaceNumber + ", CT=" + cameraTerminalId + ", PU=" + processingUnitId);
        } catch (Exception e) {
            Log.w(TAG, "discoverUvcEntities failed", e);
        }
    }

    // Imposta sempre l'altsetting 0 per l'interfaccia VC prima dei control transfer
    // Nota: gestiamo il claim e l'alt setting direttamente in initUvcConnection per la massima compatibilità

    // Rilascia interfaccia e chiude la connessione UVC per non bloccare l'external camera provider
    private void releaseUvcConnection() {
        try {
            if (uvcConnection != null) {
                try {
                    if (videoControlInterface != null) {
                        uvcConnection.releaseInterface(videoControlInterface);
                    }
                } catch (Exception ignored) {}
                try { uvcConnection.close(); } catch (Exception ignored) {}
            }
        } finally {
            uvcConnection = null;
            videoControlInterface = null;
        }
    }
}