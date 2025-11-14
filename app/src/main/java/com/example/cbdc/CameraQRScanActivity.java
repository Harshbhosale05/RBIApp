package com.example.cbdc;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraQRScanActivity extends AppCompatActivity {
    private static final String TAG = "CameraQRScanActivity";
    private static final int PERMISSION_REQUEST_CODE = 200;
    
    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private boolean isProcessing = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_qr_scan);
        
        previewView = findViewById(R.id.previewView);
        cameraExecutor = Executors.newSingleThreadExecutor();
        
        if (checkCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }
    
    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == 
               PackageManager.PERMISSION_GRANTED;
    }
    
    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.CAMERA},
            PERMISSION_REQUEST_CODE);
    }
    
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
            ProcessCameraProvider.getInstance(this);
        
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);
                
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera initialization failed", e);
                Toast.makeText(this, "Camera failed", Toast.LENGTH_SHORT).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }
    
    private void analyzeImage(androidx.camera.core.ImageProxy image) {
        if (isProcessing) {
            image.close();
            return;
        }
        
        isProcessing = true;
        
        try {
            androidx.camera.core.ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            byte[] yBuffer = new byte[plane.getBuffer().remaining()];
            plane.getBuffer().get(yBuffer);
            
            int width = image.getWidth();
            int height = image.getHeight();
            
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                yBuffer,
                width,
                height,
                0, 0,
                width,
                height,
                false
            );
            
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
            MultiFormatReader reader = new MultiFormatReader();
            
            try {
                Result result = reader.decode(binaryBitmap);
                String qrData = result.getText();
                
                runOnUiThread(() -> {
                    Intent resultIntent = new Intent();
                    resultIntent.putExtra("qr_data", qrData);
                    setResult(RESULT_OK, resultIntent);
                    finish();
                });
                
            } catch (NotFoundException e) {
                // QR code not found, continue scanning
                isProcessing = false;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Image analysis failed", e);
            isProcessing = false;
        } finally {
            image.close();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}

