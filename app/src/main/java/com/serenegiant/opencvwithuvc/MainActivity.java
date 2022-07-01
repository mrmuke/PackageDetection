/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2018 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.opencvwithuvc;

import android.animation.Animator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import androidx.annotation.NonNull;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.serenegiant.common.BaseActivity;
import com.serenegiant.opencv.ImageProcessor;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usbcameracommon.UVCCameraHandlerMultiSurface;
import com.serenegiant.utils.CpuMonitor;
import com.serenegiant.utils.ViewAnimationHelper;
import com.serenegiant.widget.UVCCameraTextureView;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends BaseActivity
	implements CameraDialog.CameraDialogParent {
	
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = "MainActivity";

	/**
	 * set true if you want to record movie using MediaSurfaceEncoder
	 * (writing frame data into Surface camera from MediaCodec
	 *  by almost same way as USBCameratest2)
	 * set false if you want to record movie using MediaVideoEncoder
	 */
    private static final boolean USE_SURFACE_ENCODER = false;

    /**
     * preview resolution(width)
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     */
    private static final int PREVIEW_WIDTH = 640;
    /**
     * preview resolution(height)
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     */
    private static final int PREVIEW_HEIGHT = 480;
    /**
     * preview mode
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     * 0:YUYV, other:MJPEG
     */
    private static final int PREVIEW_MODE = 1;


	/**
	 * for accessing USB
	 */
	private USBMonitor mUSBMonitor;
	/**
	 * Handler to execute camera related methods sequentially on private thread
	 */
	private UVCCameraHandlerMultiSurface mCameraHandler;
	/**
	 * for camera preview display
	 */
	private UVCCameraTextureView mUVCCameraView;
	/**
	 * for display resulted images
 	 */
	protected SurfaceView mResultView;
	/**
	 * for open&start / stop&close camera preview
	 */
	private ToggleButton mCameraButton;
	/**
	 * button for start/stop recording
	 */
	private ImageButton mCaptureButton;

	private View mResetButton;

	protected ImageProcessor mImageProcessor;
	private TextView mCpuLoadTv;
	private TextView mFpsTv;

	private TextView mInstructions;
	private Switch processSwitch;
	private boolean process=false;

	private final CpuMonitor cpuMonitor = new CpuMonitor();

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (DEBUG) Log.v(TAG, "onCreate:");
		setContentView(R.layout.activity_main);
		mCameraButton = findViewById(R.id.camera_button);
		mCameraButton.setOnCheckedChangeListener(mOnCheckedChangeListener);
		mCaptureButton = findViewById(R.id.capture_button);
		mCaptureButton.setOnClickListener(mOnClickListener);
		mCaptureButton.setVisibility(View.INVISIBLE);
		
		mUVCCameraView = findViewById(R.id.camera_view);
		mUVCCameraView.setOnLongClickListener(mOnLongClickListener);
		mUVCCameraView.setAspectRatio(PREVIEW_WIDTH / (float)PREVIEW_HEIGHT);

		mResultView = findViewById(R.id.result_view);


		mCpuLoadTv = findViewById(R.id.cpu_load_textview);
		mCpuLoadTv.setTypeface(Typeface.MONOSPACE);
		//
		mFpsTv = findViewById(R.id.fps_textview);
		mFpsTv.setText(null);
		mFpsTv.setTypeface(Typeface.MONOSPACE);

		mInstructions = findViewById(R.id.instructions_textview);
		mInstructions.setText(null);
		mInstructions.setTypeface(Typeface.MONOSPACE);

		processSwitch = findViewById(R.id.process_switch);
		processSwitch.setEnabled(false);

		processSwitch.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				process=processSwitch.isChecked();
				if (process){
					mResultView.setVisibility(View.VISIBLE);
				}else{

					mResultView.setVisibility(View.INVISIBLE);

				}
			}
		});



		mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
		mCameraHandler = UVCCameraHandlerMultiSurface.createHandler(this, mUVCCameraView,
			USE_SURFACE_ENCODER ? 0 : 1, PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_MODE);
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (DEBUG) Log.v(TAG, "onStart:");
		mUSBMonitor.register();
		queueEvent(mCPUMonitorTask, 1000);
		runOnUiThread(mFpsTask, 1000);
	}

	@Override
	protected void onStop() {
		if (DEBUG) Log.v(TAG, "onStop:");
		removeEvent(mCPUMonitorTask);
		removeFromUiThread(mFpsTask);
		stopPreview();
		mCameraHandler.close();
		setCameraButton(false);
		super.onStop();
	}

	@Override
	public void onDestroy() {
		if (DEBUG) Log.v(TAG, "onDestroy:");
        if (mCameraHandler != null) {
	        mCameraHandler.release();
	        mCameraHandler = null;
        }
        if (mUSBMonitor != null) {
	        mUSBMonitor.destroy();
	        mUSBMonitor = null;
        }
        mUVCCameraView = null;
        mCameraButton = null;
        mCaptureButton = null;
		super.onDestroy();
	}
	private void setTextNull(){
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				mInstructions.setText(null);

			}}

		);
	}
	/**
	 * event handler when click camera / capture button
	 */
	private final OnClickListener mOnClickListener = new OnClickListener() {
		@Override
		public void onClick(final View view) {
			switch (view.getId()) {
			case R.id.capture_button:
				if (mCameraHandler.isOpened()) {
					if (checkPermissionWriteExternalStorage() && checkPermissionAudio()) {
						if (!mCameraHandler.isRecording()) {
							mCaptureButton.setColorFilter(0xffff0000);	// turn red
							mCameraHandler.startRecording();
						} else {
							mCaptureButton.setColorFilter(0);	// return to default color
							mCameraHandler.stopRecording();
						}
					}
				}
				break;

			}
		}
	};

	private final CompoundButton.OnCheckedChangeListener mOnCheckedChangeListener
		= new CompoundButton.OnCheckedChangeListener() {
		@Override
		public void onCheckedChanged(
			final CompoundButton compoundButton, final boolean isChecked) {
			
			switch (compoundButton.getId()) {
			case R.id.camera_button:
				if (isChecked && !mCameraHandler.isOpened()) {

					CameraDialog.showDialog(MainActivity.this);
					processSwitch.setEnabled(true);
				} else {
					stopPreview();

					processSwitch.setEnabled(false);
					processSwitch.setChecked(false);
					process=false;
					mResultView.setVisibility(View.INVISIBLE);
					setTextNull();

				}
				break;
			}
		}
	};

	/**
	 * capture still image when you long click on preview image(not on buttons)
	 */
	private final OnLongClickListener mOnLongClickListener = new OnLongClickListener() {
		@Override
		public boolean onLongClick(final View view) {
			switch (view.getId()) {
			case R.id.camera_view:
				if (mCameraHandler.isOpened()) {
					if (checkPermissionWriteExternalStorage()) {
						mCameraHandler.captureStill();
					}
					return true;
				}
			}
			return false;
		}
	};

	private void setCameraButton(final boolean isOn) {
		if (DEBUG) Log.v(TAG, "setCameraButton:isOn=" + isOn);
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (mCameraButton != null) {
					try {
						mCameraButton.setOnCheckedChangeListener(null);
						mCameraButton.setChecked(isOn);
					} finally {
						mCameraButton.setOnCheckedChangeListener(mOnCheckedChangeListener);
					}
				}
				if (!isOn && (mCaptureButton != null)) {


					mCaptureButton.setVisibility(View.INVISIBLE);
				}
			}
		}, 0);
	}

	private int mPreviewSurfaceId;
	private void startPreview() {
		if (DEBUG) Log.v(TAG, "startPreview:");
		mUVCCameraView.resetFps();
		mCameraHandler.startPreview();
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					final SurfaceTexture st = mUVCCameraView.getSurfaceTexture();
					if (st != null) {
						final Surface surface = new Surface(st);
						mPreviewSurfaceId = surface.hashCode();
						mCameraHandler.addSurface(mPreviewSurfaceId, surface, false);
					}
					mCaptureButton.setVisibility(View.VISIBLE);
					startImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT);
				} catch (final Exception e) {
					Log.w(TAG, e);
				}
			}
		});
	}

	private void stopPreview() {
		if (DEBUG) Log.v(TAG, "stopPreview:");
		stopImageProcessor();
		if (mPreviewSurfaceId != 0) {
			mCameraHandler.removeSurface(mPreviewSurfaceId);
			mPreviewSurfaceId = 0;
		}
		mCameraHandler.close();
		setCameraButton(false);
	}
	
	private final OnDeviceConnectListener mOnDeviceConnectListener
		= new OnDeviceConnectListener() {
		
		@Override
		public void onAttach(final UsbDevice device) {
			Toast.makeText(MainActivity.this,
				"USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
		}

		@Override
		public void onConnect(final UsbDevice device,
			final UsbControlBlock ctrlBlock, final boolean createNew) {
			
			if (DEBUG) Log.v(TAG, "onConnect:");
			mCameraHandler.open(ctrlBlock);
			startPreview();
		}

		@Override
		public void onDisconnect(final UsbDevice device,
			final UsbControlBlock ctrlBlock) {
			
			if (DEBUG) Log.v(TAG, "onDisconnect:");
			if (mCameraHandler != null) {
				queueEvent(new Runnable() {
					@Override
					public void run() {
						stopPreview();
					}
				}, 0);
			}
		}
		@Override
		public void onDettach(final UsbDevice device) {
			Toast.makeText(MainActivity.this,
				"USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
		}

		@Override
		public void onCancel(final UsbDevice device) {
			setCameraButton(false);
		}
	};

	/**
	 * to access from CameraDialog
	 * @return
	 */
	@Override
	public USBMonitor getUSBMonitor() {
		return mUSBMonitor;
	}

	@Override
	public void onDialogResult(boolean canceled) {
		if (DEBUG) Log.v(TAG, "onDialogResult:canceled=" + canceled);
		if (canceled) {
			setCameraButton(false);
		}
	}

//================================================================================
	private boolean isActive() {
		return mCameraHandler != null && mCameraHandler.isOpened();
	}

	private boolean checkSupportFlag(final int flag) {
		return mCameraHandler != null && mCameraHandler.checkSupportFlag(flag);
	}

	private int getValue(final int flag) {
		return mCameraHandler != null ? mCameraHandler.getValue(flag) : 0;
	}

	private int setValue(final int flag, final int value) {
		return mCameraHandler != null ? mCameraHandler.setValue(flag, value) : 0;
	}

	private int resetValue(final int flag) {
		return mCameraHandler != null ? mCameraHandler.resetValue(flag) : 0;
	}




//================================================================================
	private final Runnable mCPUMonitorTask = new Runnable() {
		@Override
		public void run() {
			if (cpuMonitor.sampleCpuUtilization()) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						mCpuLoadTv.setText(String.format(Locale.US, "CPU:%3d/%3d/%3d",
							cpuMonitor.getCpuCurrent(),
							cpuMonitor.getCpuAvg3(),
							cpuMonitor.getCpuAvgAll()));
					}
				});
			}
			queueEvent(this, 1000);
		}
	};
	
	private final Runnable mFpsTask = new Runnable() {
		@Override
		public void run() {
			float srcFps, resultFps;
			if (mUVCCameraView != null) {
				mUVCCameraView.updateFps();
				srcFps = mUVCCameraView.getFps();
			} else {
				srcFps = 0.0f;
			}
			if (mImageProcessor != null) {
				mImageProcessor.updateFps();
				resultFps = mImageProcessor.getFps();
			} else {
				resultFps = 0.0f;
			}
			mFpsTv.setText(String.format(Locale.US, "FPS:%4.1f->%4.1f", srcFps, resultFps));
			runOnUiThread(this, 1000);
		}
	};

//================================================================================
	private volatile boolean mIsRunning;
	private int mImageProcessorSurfaceId;
	
	/**
	 * start image processing
	 * @param processing_width
	 * @param processing_height
	 */
	protected void startImageProcessor(final int processing_width, final int processing_height) {
		if (DEBUG) Log.v(TAG, "startImageProcessor:");
		mIsRunning = true;
		if (mImageProcessor == null) {
			mImageProcessor = new ImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT,	// src size
				new MyImageProcessorCallback(processing_width, processing_height));	// processing size
			mImageProcessor.start(processing_width, processing_height);	// processing size
			final Surface surface = mImageProcessor.getSurface();
			mImageProcessorSurfaceId = surface != null ? surface.hashCode() : 0;
			if (mImageProcessorSurfaceId != 0) {
				mCameraHandler.addSurface(mImageProcessorSurfaceId, surface, false);
			}
		}
	}
	
	/**
	 * stop image processing
	 */
	protected void stopImageProcessor() {
		if (DEBUG) Log.v(TAG, "stopImageProcessor:");
		if (mImageProcessorSurfaceId != 0) {
			mCameraHandler.removeSurface(mImageProcessorSurfaceId);
			mImageProcessorSurfaceId = 0;
		}
		if (mImageProcessor != null) {
			mImageProcessor.release();
			mImageProcessor = null;
		}
	}
	private class TurnDirection{
		double degrees;
		boolean turnRight;
		TurnDirection(double degrees, boolean turnRight){
			this.degrees=degrees;
			this.turnRight=turnRight;
		}
	}
	/**
	 * callback listener from `ImageProcessor`
	 */

	protected class MyImageProcessorCallback implements ImageProcessor.ImageProcessorCallback {
		private final int width, height;
		private final Matrix matrix = new Matrix();
		private Bitmap mFrame;
		protected MyImageProcessorCallback(
			final int processing_width, final int processing_height) {
			
			width = processing_width;
			height = processing_height;
		}
		public void drawCrosshair(Mat img ){
			Imgproc.line(img,new Point(width/2-20,height/2), new Point(width/2+20,height/2),new Scalar(120,100,100), 1);
			Imgproc.line(img,new Point(width/2,height/2-20), new Point(width/2,height/2+20),new Scalar(120,100,100), 1);
		}
		public double getSlope(Point p1, Point p2){
			return ((double)(p2.y - p1.y) / (double)(p2.x-p1.x));
		}
		public Point getCenter(Rect boundingBox){
			Point center = new Point();
			center.x= boundingBox.x+boundingBox.width/2;
			center.y=boundingBox.y+boundingBox.height/2;
			return center;
		}
		public Point getMidPoint(Point point1,Point point2){
			Point center = new Point();
			center.x= (point1.x+point2.x)/2;
			center.y=(point1.y+point2.y)/2;
			return center;
		}
		public TurnDirection getDegreesFromHorizontal(Point point1, Point point2){
			boolean turnRight=false;

			if(point1.x<point2.x){
				if(point1.y<point2.y){
					turnRight=true;
				}
			}
			else{
				if(point2.y<point1.y){
					turnRight=true;
				}
			}

			double heightOfTriangle = Math.abs(point1.y-point2.y);
			double widthOfTriangle = Math.abs(point1.x-point2.x);
			double degrees = Math.atan2(heightOfTriangle,widthOfTriangle);
			return new TurnDirection(degrees*(180/3.14),turnRight);


		}

		public double getDistance(Point point1, Point point2){
			return Math.sqrt(Math.pow(point1.x-point2.x,2)+Math.pow(point1.y-point2.y,2));
		}
		public void drawBoundingBox(Rect boundingBox, Mat img){

			Point pt1=new Point();
			Point pt2= new Point();
			pt1.x = boundingBox.x;
			pt1.y = boundingBox.y;
			pt2.x = boundingBox.x + boundingBox.width;
			pt2.y = boundingBox.y + boundingBox.height;

			Imgproc.rectangle(img, pt1,pt2, new Scalar(120,100,100),2);
		}
		@Override
		public void onFrame(final ByteBuffer frame) {
			if(process) {


				byte[] data = new byte[frame.capacity()];
				((ByteBuffer) frame.duplicate().clear()).get(data);
				Mat frameMat = new Mat(height, width, CvType.CV_8UC4);

				frameMat.put(0, 0, data);
				Mat gray = new Mat();
				Imgproc.cvtColor(frameMat, gray, Imgproc.COLOR_BGR2GRAY);
				Mat blurred = new Mat();
				Imgproc.GaussianBlur(gray, blurred, new Size(11, 11), 0);
				Mat threshold = new Mat();
				Imgproc.threshold(blurred, threshold, 210, 255, Imgproc.THRESH_BINARY);


				Mat hierarchy = new Mat();
				List<MatOfPoint> contours = new ArrayList<>();
				Imgproc.findContours(threshold, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);
				if (contours.size() == 0) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							if(process){
								mInstructions.setText("Nothing Detected");
							}

						}
					});

				} else if (contours.size() == 1) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							if(process) {
								mInstructions.setText("Only One Detected");
							}
						}
					});
				} else if (contours.size() == 2) {
					Scalar grey = new Scalar(100, 120, 100);
					String text = "";
					//center of camera
					Point center = new Point(width / 2, height / 2);
					Rect boundingBox1 = Imgproc.boundingRect(contours.get(0));
					Rect boundingBox2 = Imgproc.boundingRect(contours.get(1));

					//drawing bounding boxes
					drawBoundingBox(boundingBox1, threshold);
					drawBoundingBox(boundingBox2, threshold);

					Point boundingBox1Center = getCenter(boundingBox1);
					Point boundingBox2Center = getCenter(boundingBox2);

					//draw line between 2 tapes
					Imgproc.line(threshold, boundingBox1Center, boundingBox2Center, grey);
					//draw line between center of camera view and midpoint of two tapes
					Point midPoint = getMidPoint(boundingBox1Center, boundingBox2Center);
					Imgproc.line(threshold, midPoint, center, grey, 1);

					//tell which direction to turn and how many degrees
					if (midPoint.y > center.y) {
						text = "Turn Around";
					} else {
						TurnDirection turnDirection = getDegreesFromHorizontal(boundingBox1Center, boundingBox2Center);
						//if horizontal
						if (turnDirection.degrees < 5) {
							Point dest = new Point(midPoint.x, midPoint.y + 100);
							double distance = getDistance(center, midPoint);
							Imgproc.arrowedLine(threshold, center, dest, grey);
							text = "Position";
							if (Math.abs(distance - 100) < 20) {
								//draw rectangles as right angle symbols
								Imgproc.rectangle(threshold, midPoint, new Point(midPoint.x + 10, midPoint.y +10), grey);
								Imgproc.rectangle(threshold, midPoint, new Point(midPoint.x - 10, midPoint.y +10), grey);
								//done positioning - ready to fly to right altitude
								text = "Ready to Descend";
							}
						} else {
							text = String.format("Turn %.2f",turnDirection.degrees) + (turnDirection.turnRight ? " CW" : " CCW");
						}
					}
					final String finalText = text;
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							if(process) {
								mInstructions.setText(finalText);
							}
						}
					});


				} else {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							if(process) {
								mInstructions.setText("Too many?");
							}
						}
					});
				}

				drawCrosshair(threshold);
			/*List<MatOfPoint> contours = new ArrayList<>();
			Mat hierarchy = new Mat();
			Mat gray = new Mat();
			Imgproc.cvtColor(frameMat,gray, Imgproc.COLOR_BGR2GRAY);

			Mat binary = new Mat(frameMat.rows(), frameMat.cols(), frameMat.type(), new Scalar(0));
			Imgproc.threshold(gray, binary, 100, 255, Imgproc.THRESH_BINARY_INV);
			Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE); // Find the contours in the image

			double biggestArea = 0;

			Log.d(TAG,""+contours.size()+" contours");

			Rect biggestBounding=new Rect();
			for(int i=0; i<contours.size(); i++)
			{

				double area = Imgproc.contourArea(contours.get(i));
				if(area>biggestArea&&area<40000){
				{
					biggestArea=area;
					biggestBounding=Imgproc.boundingRect(contours.get(i));

				}}
			}
			Log.d(TAG,"Biggest Area: "+biggestArea);


			Point pt1=new Point();
			Point pt2= new Point();
			pt1.x = biggestBounding.x;
			pt1.y = biggestBounding.y;
			pt2.x = biggestBounding.x + biggestBounding.width;
			pt2.y = biggestBounding.y + biggestBounding.height;
			Point center = new Point();
			center.x= biggestBounding.x+biggestBounding.width/2;
			center.y=biggestBounding.y+biggestBounding.height/2;
			Log.d(TAG,pt1.toString());
			Log.d(TAG,pt2.toString());
			Imgproc.rectangle(frameMat, pt1,pt2, new Scalar(120,100,100),2);*/
				if (mResultView != null) {
					final SurfaceHolder holder = mResultView.getHolder();
					if ((holder == null)
							|| (holder.getSurface() == null)
							|| (frame == null)) return;


					if (mFrame == null) {
						mFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

						final float scaleX = mResultView.getWidth() / (float) width;
						final float scaleY = mResultView.getHeight() / (float) height;
						matrix.reset();
						matrix.postScale(scaleX, scaleY);
					}
					try {
						frame.clear();

						Utils.matToBitmap(threshold, mFrame);
						final Canvas canvas = holder.lockCanvas();
						if (canvas != null) {
							try {

								canvas.drawBitmap(mFrame, matrix, null);
							} catch (final Exception e) {
								Log.w(TAG, e);
							} finally {
								holder.unlockCanvasAndPost(canvas);
							}
						}
					} catch (final Exception e) {
						Log.w(TAG, e);
					}
				}
			}
		}

		@Override
		public void onResult(final int type, final float[] result) {
			// do something
		}
		
	}

}
