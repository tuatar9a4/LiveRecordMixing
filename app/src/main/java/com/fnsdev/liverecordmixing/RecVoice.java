package com.fnsdev.liverecordmixing;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.util.Log;

import com.opentok.android.BaseAudioDevice;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class RecVoice extends  BaseAudioDevice{

    //20200610 iwarrior 녹음 옵션
    private final static boolean bRec = false;
    //20200618 iwarrior 볼륨
    float mVol = 1;

    private final static String LOG_TAG =  RecVoice.class.getSimpleName();

    private static final int NUM_CHANNELS_CAPTURING = 1;
    private static final int NUM_CHANNELS_RENDERING = 1;
    private static final int STEREO_CHANNELS = 2;
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int SAMPLE_SIZE_IN_BYTES = 2;
    private static final int DEFAULT_SAMPLES_PER_BUFFER = (DEFAULT_SAMPLE_RATE / 1000) * 10; // 10ms
    private static final int DEFAULT_BUFFER_SIZE =
            SAMPLE_SIZE_IN_BYTES * DEFAULT_SAMPLES_PER_BUFFER * STEREO_CHANNELS;
    // Max 10 ms @ 48 kHz - Stereo

    //20200506 iwarrior
//    private int bufferSize;
    private static final int RECORDER_SAMPLERATE = 44100;
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private int bufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING)*3;

    //20200508 iwarrior
    private int pcmBufferSize = 0;

    private Context context;

    private AudioTrack audioTrack;
    private AudioRecord audioRecord;

    // Capture & render buffers
    private ByteBuffer playBuffer;
    private ByteBuffer recBuffer;
    private byte[] tempBufPlay;
    private byte[] tempBufRec;

    //20200409 iwarrior
    private byte[] tempPcmBuffer;

    //20200608 iwarrior
    int recBufSize = 0;

    private final ReentrantLock rendererLock = new ReentrantLock(true);
    private final Condition renderEvent = rendererLock.newCondition();
    private volatile boolean isRendering = false;
    private volatile boolean shutdownRenderThread = false;

    private final ReentrantLock captureLock = new ReentrantLock(true);
    private final Condition captureEvent = captureLock.newCondition();
    private volatile boolean isCapturing = false;
    public volatile boolean shutdownCaptureThread = false;

    private AudioSettings captureSettings;
    private AudioSettings rendererSettings;
    private NoiseSuppressor noiseSuppressor;
    private AcousticEchoCanceler echoCanceler;

    private OutputType audioOutput = OutputType.PHONE_SPEAKERS;

    // Capturing delay estimation
    private int estimatedCaptureDelay = 0;

    // Rendering delay estimation
    private int bufferedPlaySamples = 0;
    private int playPosition = 0;
    private int estimatedRenderDelay = 0;

    private AudioManager audioManager;
    private AudioManagerMode audioManagerMode = new AudioManagerMode();

    private int outputSamplingRate = DEFAULT_SAMPLE_RATE;
    private int captureSamplingRate = DEFAULT_SAMPLE_RATE;
    private int samplesPerBuffer = DEFAULT_SAMPLES_PER_BUFFER;

    // for headset receiver
    private static final String HEADSET_PLUG_STATE_KEY = "state";

    // for bluetooth
    private BluetoothState bluetoothState;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothProfile bluetoothProfile;
    private Object bluetoothLock = new Object();

    //20200523 iwarrior 채점을 위한 변수
    private int mScore = 0;
    private int mScoreCount = 0;
    private FileChannel fileChannelVoice;
    private String sRecAudioFile;

    private enum BluetoothState {
        DISCONNECTED, CONNECTED
    }

    private enum OutputType {
        PHONE_SPEAKERS,     /* speaker-phone & ear-piece */
        EAR_PIECE,
        HEAD_PHONES,
        BLUETOOTH
    }

    private OutputType audioOutputType = OutputType.PHONE_SPEAKERS;

    private OutputType getOutputType() {
        return audioOutputType;
    }

    private void setOutputType(OutputType type) {
        audioOutputType = type;
    }

    private static class AudioManagerMode {
        private int oldMode;
        private int naquire;

        public AudioManagerMode() {
            oldMode = 0;
            naquire = 0;
        }

        public void acquireMode(AudioManager audioManager) {
            if (0 == naquire++) {
                oldMode = audioManager.getMode();
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }
        }

        public void releaseMode(AudioManager audioManager) {
            if (0 == --naquire) {
                audioManager.setMode(oldMode);
            }
        }
    }

    // 210326
    // 메모리 해제가 안돼 이전에 실해된 class가 남아 오류가 발생해 singleton으로 변경
    private static RecVoice instance;
    public static RecVoice getInstance(Context context) {
        if(instance == null){
            instance = new RecVoice(context);
        }
        return instance;
    }

    private static class AudioState {
        private int lastStreamVolume = 0;
        private int lastKnownFocusState = 0;
        private OutputType lastOutputType = OutputType.PHONE_SPEAKERS;

        int getLastStreamVolume() {
            return lastStreamVolume;
        }

        void setLastStreamVolume(int lastStreamVolume) {
            this.lastStreamVolume = lastStreamVolume;
        }

        int getLastKnownFocusState() {
            return lastKnownFocusState;
        }

        void setLastKnownFocusState(int lastKnownFocusState) {
            this.lastKnownFocusState = lastKnownFocusState;
        }

        OutputType getLastOutputType() {
            return this.lastOutputType;
        }

        void setLastOutputType(OutputType lastOutputType) {
            this.lastOutputType = lastOutputType;
        }

    }

    private AudioState audioState = new AudioState();


    private final BroadcastReceiver btStatusReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (null != action && action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1);
                switch (state) {
                    case BluetoothHeadset.STATE_CONNECTED:
                        synchronized (bluetoothLock) {
                            if (BluetoothState.DISCONNECTED == bluetoothState) {
                                bluetoothState = BluetoothState.CONNECTED;
                                setOutputType(OutputType.BLUETOOTH);
                                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                                audioManager.setBluetoothScoOn(true);
                                startBluetoothSco();
                                audioManager.setSpeakerphoneOn(false);
                            }
                        }
                        break;
                    case BluetoothHeadset.STATE_DISCONNECTED:
                        synchronized (bluetoothLock) {
                            if (BluetoothState.CONNECTED == bluetoothState) {
                                bluetoothState = BluetoothState.DISCONNECTED;
                                audioManager.setBluetoothScoOn(false);
                                stopBluetoothSco();
                                if (audioManager.isWiredHeadsetOn()) {
                                    setOutputType(OutputType.HEAD_PHONES);
                                    audioManager.setSpeakerphoneOn(false);
                                } else {
                                    setOutputType(OutputType.PHONE_SPEAKERS);
                                    audioManager.setSpeakerphoneOn(
                                            getOutputMode() == OutputMode.SpeakerPhone
                                    );
                                }
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    };

    private void connectBluetooth() {
        audioManager.setBluetoothScoOn(true);
        startBluetoothSco();
    }

    private final BluetoothProfile.ServiceListener bluetoothProfileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int type, BluetoothProfile profile) {
            if  (BluetoothProfile.HEADSET == type) {
                bluetoothProfile = profile;
                List<BluetoothDevice> devices = profile.getConnectedDevices();
                if (!devices.isEmpty()
                        && BluetoothHeadset.STATE_CONNECTED == profile.getConnectionState(devices.get(0))) {
            /* force a init of bluetooth: the handler will not send a connected event if a
               device is already connected at the time of proxy connection request. */
                    Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
                    intent.putExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_CONNECTED);
                    btStatusReceiver.onReceive(context, intent);
                }
            }
        }

        @Override
        public void onServiceDisconnected(int type) {
        }
    };


    String sRecAudioFileWav="";
    public RecVoice(Context context) {
        this.context = context;

        //20200610 iwarrior 녹음 옵션
            sRecAudioFile = context.getFilesDir().getPath().toString() + "/custom.pcm";
        sRecAudioFileWav=sRecAudioFile.replace(".pcm",".wav");
            Log.d("도원 라이브녹음","파일 경로 : "+sRecAudioFile);
            try {
                File f = new File(sRecAudioFile);
                f.delete();

                fileChannelVoice = new FileOutputStream(sRecAudioFile).getChannel();// ;//.getChannel();
            }catch (IOException _e) {
            }
        try {
            recBuffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE);
        } catch (Exception e) {
        }
        tempBufRec = new byte[DEFAULT_BUFFER_SIZE];

        audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        bluetoothProfile = null;

        int outputBufferSize = DEFAULT_BUFFER_SIZE;

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN) {
            try {
                outputSamplingRate = Integer.parseInt(
                        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
            } finally {
                if (outputSamplingRate == 0) {
                    outputSamplingRate = DEFAULT_SAMPLE_RATE;
                }
            }
            try {
                samplesPerBuffer = Integer.parseInt(
                        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
            } catch(NumberFormatException numberFormatException) {
                numberFormatException.printStackTrace();
            } finally {
                if (outputBufferSize == 0) {
                    outputBufferSize = DEFAULT_BUFFER_SIZE;
                    samplesPerBuffer = DEFAULT_SAMPLES_PER_BUFFER;
                }
            }
        }

        try {
            playBuffer = ByteBuffer.allocateDirect(outputBufferSize);
        } catch (Exception e) {
            Log.e(LOG_TAG, e.getMessage());
        }
        //20200508 iwarrior
        pcmBufferSize = outputBufferSize * 2;
        tempPcmBuffer = new byte[pcmBufferSize];
        tempBufPlay = new byte[outputBufferSize];

        captureSettings = new AudioSettings(captureSamplingRate, NUM_CHANNELS_CAPTURING);
        rendererSettings = new AudioSettings(outputSamplingRate, NUM_CHANNELS_RENDERING);
    }

    //20200508 iwarrior
    public int getPcmBufferSize(){
        return pcmBufferSize;
    }

    //20200409 iwarrior 재생 데이터를 가져온다.
    public void setPlayBuffer(byte[] pBuf){}

    //20200508 iwarrior 스트리밍 송출 함수를 따로뺀다.
    //믹싱 데이타가 완료되면 호출.
    public void sendAudioData(ByteBuffer pBuf, int nLen){
        recBuffer.rewind();
        recBuffer.put(pBuf);
        getAudioBus().writeCaptureData(recBuffer, nLen);
    }

    @Override
    public boolean initCapturer() {
        Log.d("도원 initCapturer"," initCapturer in");
        // initalize audio mode
        // get the minimum buffer size that can be used
        int minRecBufSize = AudioRecord.getMinBufferSize(
                captureSettings.getSampleRate(),
                NUM_CHANNELS_CAPTURING == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        // double size to be more safe
        int recBufSize = minRecBufSize * 2;
        // release the object
        if (noiseSuppressor != null) {
            noiseSuppressor.release();
            noiseSuppressor = null;
        }
        if (echoCanceler != null) {
            echoCanceler.release();
            echoCanceler = null;
        }
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        try {
            /*
            audioRecord = new AudioRecord(AudioSource.CAMCORDER,
>>>>>>> Stashed changes
                    captureSettings.getSampleRate(),
                    NUM_CHANNELS_CAPTURING == 1 ? AudioFormat.CHANNEL_IN_MONO
                            : AudioFormat.CHANNEL_IN_STEREO,
                    RECORDER_AUDIO_ENCODING, recBufSize);*/
            //입력 장치를 CAMCORDER에서 MIC 로 변경
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    captureSettings.getSampleRate(),
                    NUM_CHANNELS_CAPTURING == 1 ? AudioFormat.CHANNEL_IN_MONO
                            : AudioFormat.CHANNEL_IN_STEREO,
                    RECORDER_AUDIO_ENCODING, recBufSize);

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioRecord.getAudioSessionId());
            }
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
            }

        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
        // check that the audioRecord is ready to be used
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            return false;
        }


        shutdownCaptureThread = false;
        new Thread(captureThread).start();
        return true;
    }

    @Override
    public boolean destroyCapturer() {
        captureLock.lock();
        // release the object
        if (null != echoCanceler) {
            echoCanceler.release();
            echoCanceler = null;
        }
        if (null != noiseSuppressor) {
            noiseSuppressor.release();
            noiseSuppressor = null;
        }
        if(audioRecord != null){
            audioRecord.release();
            audioRecord = null;
        }

        shutdownCaptureThread = true;
        captureEvent.signal();

        captureLock.unlock();
        // shutdown audio mode
        return true;
    }

    public int getEstimatedCaptureDelay() {
        return estimatedCaptureDelay;
    }

    public void captureThreadStop(){
        shutdownCaptureThread = true;
    }

    @Override
    public boolean startCapturer() {
        // start recording
        try {
            audioRecord.startRecording();

        } catch (IllegalStateException e) {
            throw new RuntimeException(e.getMessage());
        }



        captureLock.lock();
        isCapturing = true;
        captureEvent.signal();
        captureLock.unlock();
        return true;
    }

    @Override
    public boolean stopCapturer() {
        if (audioRecord == null) return false;

        captureLock.lock();
        try {
            // only stop if we are recording
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                // stop recording
                audioRecord.stop();
                //20200610 iwarrior 녹음 옵션
                    fileChannelVoice.close();

                CopyWaveFile(sRecAudioFile,sRecAudioFileWav,2);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Ensure we always unlock
            isCapturing = false;
            captureLock.unlock();
        }
        return true;
    }

    Runnable captureThread = new Runnable() {
        @Override
        public void run() {
            int samplesToRec = captureSamplingRate / 100;
            int samplesRead = 0;
            try {
                android.os.Process
                        .setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            } catch (Exception e) {
                // thread priority isn't fatal, just not 'great' so no failure exception thrown
                e.printStackTrace();
            }
            int iSeq = 0;
            ByteBuffer tempBuffer = null;
            byte[] tempArray = null;
            byte[] saveBuf1 = null;

            while (!shutdownCaptureThread) {
                captureLock.lock();
                try {
                    if (!RecVoice.this.isCapturing) {
                        captureEvent.await();
                        continue;
                    } else {
                        if (audioRecord == null) {
                            continue;
                        }
                        int lengthInBytes = (samplesToRec << 1) * NUM_CHANNELS_CAPTURING;
                        // 마이크 입력 데이터를 버퍼로 얻어오는 부분
                        int readBytes = audioRecord.read(tempBufRec, 0, lengthInBytes);
                        if (readBytes >= 0) {
                            //20200509 iwarrior 재생 중의 경우만 믹싱을 한다.
                            if(((MainActivity) context).isPlaySong() == true){
                                tempArray = new byte[readBytes * 2];
//                                if(iSeq == 0) {
                                    tempBuffer = ByteBuffer.allocate((int) readBytes*2);
                                    // 재생되는 mr 데이터를 버퍼로 얻어오는 부분
                                    int nRead = ((MainActivity) context).songCtrl.getPlayData(tempBuffer, readBytes*2);
                                    Log.d("도원 ","nRead : "+nRead+"/"+readBytes*2);
                                    saveBuf1 = new byte[readBytes * 4 * 10];
//                                    tempBuffer.get(saveBuf1, 0, readBytes * 2 * 10);
                                    //20200610 iwarrior 녹음 옵션
                                    fileChannelVoice.write(tempBuffer);
//                                }

//                                tempArray = new byte[readBytes * 4];
//
//                                // mr 정보에 (iSeq * readBytes *2) 위치 값을 tempArray에 0번째로 마이크 정보 길이 만큼 복사
//                                System.arraycopy( saveBuf1, iSeq * readBytes *2, tempArray, 0, readBytes *2 );
//                                iSeq++;
//                                if(iSeq >= 10) iSeq = 0;
//
//                                //20200527 iwarrior 채점
//                                double dSum = 0;
//                                byte[] mixArray = new byte[DEFAULT_BUFFER_SIZE];
//                                //20200527 iwarrior 믹싱 로직을 원시적으로 처리함.
//                                //tempArray = MR의 Byte[] 값 tempBufRec = Voice의 byte[]값
//                                for(int i = 0; i < readBytes/2; i++){
//                                    mixArray[i*2] = (byte)((tempArray[i*4] + tempArray[i*4+2] + tempBufRec[i*2])/3);
//                                    Log.d("도원 라이브녹음",tempBufRec[i*2]/3+"");
//                                    mixArray[i*2+1] = (byte)((tempArray[i*4+1] + tempArray[i*4+3] + tempBufRec[i*2+1])/3);
//                                    dSum += Math.abs(tempBufRec[i*2]);
//                                }
//
//                                byte[] saveBuf = new byte[readBytes];
//                                for(int i = 0; i < readBytes; i++) {
//                                    saveBuf[i] = mixArray[i];
//                                }
//                                recBuffer.rewind();
//                                //  recBuffer.put(saveBuf);
//                                recBuffer.put(saveBuf);
                                //20200527 iwarrior 채점 카운트.
//                                boolean bLyric = ((liveKaraokeActivity) context).isLyricTime();
//                                dSum = dSum / readBytes / 2;
//
//                                if(bLyric){
//                                    if(dSum > 7){
//                                        mScore++;
//                                    }
//                                    mScoreCount++;
//                                }
                            }else{
                                recBuffer.rewind();
                                recBuffer.put(tempBufRec);
                            }

                            samplesRead = (readBytes >> 1) / NUM_CHANNELS_CAPTURING;
                        } else {
                            switch (readBytes) {
                                case AudioRecord.ERROR_BAD_VALUE:
                                    throw new RuntimeException("Audio Capture Error: Bad Value (-2)");
                                case AudioRecord.ERROR_INVALID_OPERATION:
                                    throw new RuntimeException("Audio Capture Error: Invalid Operation (-3)");
                                case AudioRecord.ERROR:
                                default:
                                    throw new RuntimeException("Audio Capture Error(-1)");
                            }

                        }
                    }
                } catch (Exception e) {
                    Log.d("도원 ","Exception : "+e.getMessage()+"// Exception : "+e.getStackTrace());
                    e.printStackTrace();
                    return;
                } finally {
                    // Ensure we always unlock
                    captureLock.unlock();
                }
                //20200408 iwarrior 전송 파트로 내보낸다.
                estimatedCaptureDelay = samplesRead * 1000 / captureSamplingRate;
            }
        }
    };

    @Override
    public boolean initRenderer() {
        // initalize default values
        bluetoothState = BluetoothState.DISCONNECTED;
        // initalize audio mode
        audioManagerMode.acquireMode(audioManager);
        // set default output routing
        setOutputMode(getOutputMode());
        /* register for bluetooth sco callbacks and attempt to enable it */
        enableBluetoothEvents();
        // get the minimum buffer size that can be used
        int minPlayBufSize = AudioTrack.getMinBufferSize(
                rendererSettings.getSampleRate(),
                NUM_CHANNELS_RENDERING == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO,
                RECORDER_AUDIO_ENCODING
        );

        // release the object
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }

        try {
            audioTrack  = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    new AudioFormat.Builder()
                            .setChannelMask((NUM_CHANNELS_RENDERING == 1)
                                    ? AudioFormat.CHANNEL_OUT_MONO
                                    : AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(RECORDER_AUDIO_ENCODING)
                            .setSampleRate(rendererSettings.getSampleRate())
                            .build(),
                    minPlayBufSize >= 6000 ? minPlayBufSize : minPlayBufSize * 2,
                    AudioTrack.MODE_STREAM,
                    audioManager.generateAudioSessionId()
            );
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
        // check that the audioRecord is ready to be used
        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new RuntimeException("Audio renderer not initialized " + rendererSettings.getSampleRate());
        }

        bufferedPlaySamples = 0;
        shutdownRenderThread = false;
        new Thread(renderThread).start();
        return true;
    }

    private void destroyAudioTrack() {
        rendererLock.lock();
        // release the object
        if(audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
        shutdownRenderThread = true;
        renderEvent.signal();
        rendererLock.unlock();
    }

    @Override
    public boolean destroyRenderer() {
        destroyAudioTrack();
        disableBluetoothEvents();
        unregisterHeadsetReceiver();
        audioManager.setSpeakerphoneOn(false);
        audioManagerMode.releaseMode(audioManager);
        return true;
    }

    public int getEstimatedRenderDelay() {
        return estimatedRenderDelay;
    }

    @Override
    public boolean startRenderer() {
        /* enable speakerphone unless headset is conencted */
        synchronized (bluetoothLock) {
            if (BluetoothState.CONNECTED != bluetoothState) {
                if (audioManager.isWiredHeadsetOn()) {
                    Log.d(LOG_TAG, "Turn off Speaker phone");
                    audioManager.setSpeakerphoneOn(false);
                } else {
                    Log.d(LOG_TAG, "Turn on Speaker phone");
                    audioManager.setSpeakerphoneOn(true);
                }
            }
        }

        // Start playout.
        if (audioTrack == null) {
            throw new IllegalStateException("startRenderer(): play() called on uninitialized AudioTrack");
        }

        try {
            audioTrack.play();
        } catch (IllegalStateException e) {
            throw new RuntimeException(e.getMessage());
        }
        rendererLock.lock();
        isRendering = true;
        renderEvent.signal();
        rendererLock.unlock();

        registerHeadsetReceiver();
        return true;
    }

    @Override
    public boolean stopRenderer() {

        if (audioTrack == null) return false;

        rendererLock.lock();
        try {
            // only stop if we are playing
            if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                // stop playout
                audioTrack.stop();

            }
            // flush the buffers
            audioTrack.flush();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Ensure we always unlock, both for success, exception or error
            // return.
            isRendering = false;
            rendererLock.unlock();
        }
        unregisterHeadsetReceiver();
        unregisterBtReceiver();
        return true;
    }

    Runnable renderThread = new Runnable() {

        @Override
        public void run() {
            int samplesToPlay = samplesPerBuffer;
            try {
                android.os.Process
                        .setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            } catch (Exception e) {
                // thread priority isn't fatal, just not 'great' so no failure exception thrown
                e.printStackTrace();
            }

            while (!shutdownRenderThread) {
                rendererLock.lock();
                try {
                    if (!RecVoice.this.isRendering) {
                        renderEvent.await();
                        continue;

                    } else {
                        rendererLock.unlock();

                        // Don't lock on audioBus calls
                        playBuffer.clear();
                        //20200408 iwarrior 전송되는 데이터를 가져와서 재생 파트로 넘긴다.
                        int samplesRead = getAudioBus().readRenderData(playBuffer, samplesToPlay);
                        rendererLock.lock();

                        // After acquiring the lock again
                        // we must check if we are still playing
                        if (audioTrack == null || !RecVoice.this.isRendering) {
                            continue;
                        }

                        int bytesRead = (samplesRead << 1) * NUM_CHANNELS_RENDERING;
                        playBuffer.get(tempBufPlay, 0, bytesRead);

                        int bytesWritten = audioTrack.write(tempBufPlay, 0, bytesRead);
                        if (bytesWritten > 0) {
                            // increase by number of written samples
                            bufferedPlaySamples += (bytesWritten >> 1) / NUM_CHANNELS_RENDERING;

                            // decrease by number of played samples
                            int pos = audioTrack.getPlaybackHeadPosition();
                            if (pos < playPosition) {
                                // wrap or reset by driver
                                playPosition = 0;
                            }
                            bufferedPlaySamples -= (pos - playPosition);
                            playPosition = pos;

                            // we calculate the estimated delay based on the buffered samples
                            estimatedRenderDelay = bufferedPlaySamples * 1000 / outputSamplingRate;
                        } else {
                            switch (bytesWritten) {
                                case AudioTrack.ERROR_BAD_VALUE:
                                    throw new RuntimeException(
                                            "Audio Renderer Error: Bad Value (-2)");
                                case AudioTrack.ERROR_INVALID_OPERATION:
                                    throw new RuntimeException(
                                            "Audio Renderer Error: Invalid Operation (-3)");
                                case AudioTrack.ERROR:
                                default:
                                    throw new RuntimeException(
                                            "Audio Renderer Error(-1)");
                            }
                        }
                    }
                } catch (Exception e) {
                    return;
                } finally {
                    rendererLock.unlock();
                }
            }
        }
    };

    @Override
    public AudioSettings getCaptureSettings() {
        return this.captureSettings;
    }

    @Override
    public AudioSettings getRenderSettings() {
        return this.rendererSettings;
    }

    /**
     * Communication modes handling.
     */
    public boolean setOutputMode(OutputMode mode) {
        super.setOutputMode(mode);
        if(OutputMode.SpeakerPhone == mode) {
            audioState.setLastOutputType(getOutputType());
            setOutputType(OutputType.PHONE_SPEAKERS);
            audioManager.setSpeakerphoneOn(true);
            stopBluetoothSco();
            audioManager.setBluetoothScoOn(false);
        } else {
            if (audioState.getLastOutputType() == OutputType.BLUETOOTH || bluetoothState == BluetoothState.CONNECTED) {
                connectBluetooth();
            } else {
                audioState.setLastOutputType(getOutputType());
                audioManager.setSpeakerphoneOn(false);
                setOutputType(OutputType.EAR_PIECE);
                stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
            }
        }

        return true;
    }

    private BroadcastReceiver headsetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_HEADSET_PLUG)) {
                if (intent.getIntExtra(HEADSET_PLUG_STATE_KEY, 0) == 1) {
                    audioState.setLastOutputType(getOutputType());
                    setOutputType(OutputType.HEAD_PHONES);
                    audioManager.setSpeakerphoneOn(false);
                    audioManager.setBluetoothScoOn(false);
                } else {
                    if (getOutputType() == OutputType.HEAD_PHONES) {
                        if (audioState.getLastOutputType() == OutputType.BLUETOOTH &&
                                BluetoothState.CONNECTED == bluetoothState) {
                            audioManager.setBluetoothScoOn(true);
                            startBluetoothSco();
                            setOutputType(OutputType.BLUETOOTH);
                        } else {
                            if (audioState.getLastOutputType() == OutputType.PHONE_SPEAKERS) {
                                setOutputType(OutputType.PHONE_SPEAKERS);
                                audioManager.setSpeakerphoneOn(true);
                            }
                            if (audioState.getLastOutputType() == OutputType.EAR_PIECE) {
                                setOutputType(OutputType.EAR_PIECE);
                                audioManager.setSpeakerphoneOn(false);
                            }
                        }
                    }
                }
            }
        }
    };

    private boolean receiverRegistered;
    private boolean scoReceiverRegistered;

    private void registerHeadsetReceiver() {
        if (!receiverRegistered) {
            context.registerReceiver(headsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
            receiverRegistered = true;
        }
    }

    public void unregisterHeadsetReceiver() {
        if (receiverRegistered) {
            context.unregisterReceiver(headsetReceiver);
            receiverRegistered = false;
        }
    }

    private void registerBtReceiver() {
        if (!scoReceiverRegistered) {
            context.registerReceiver(
                    btStatusReceiver,
                    new IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            );
            scoReceiverRegistered = true;
        }
    }

    private void unregisterBtReceiver() {
        if (scoReceiverRegistered) {
            try {
                context.unregisterReceiver(btStatusReceiver);
                scoReceiverRegistered = false;
            }catch (IllegalArgumentException e){
                e.printStackTrace();
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    @Override
    public synchronized void onPause() {
        if (isRendering && getOutputMode() == OutputMode.SpeakerPhone) {
            unregisterBtReceiver();
            unregisterHeadsetReceiver();
        }
    }

    @Override
    public synchronized void onResume() {
        /* register handler for phonejack notifications */
        if (isRendering && getOutputMode() == OutputMode.SpeakerPhone) {
            registerHeadsetReceiver();
            if (!audioManager.isWiredHeadsetOn()) {
                audioManager.setSpeakerphoneOn(true);
            }
        }
        /* force reconnection of bluetooth in the event of a phone call */
        synchronized (bluetoothLock) {
            if (audioOutput == OutputType.BLUETOOTH) {
                bluetoothState = BluetoothState.DISCONNECTED;
                if (bluetoothAdapter != null) {
                    bluetoothAdapter.getProfileProxy(
                            context,
                            bluetoothProfileListener,
                            BluetoothProfile.HEADSET

                    );
                }
            }
        }
    }

    private void enableBluetoothEvents() {
        if (audioManager.isBluetoothScoAvailableOffCall()) {
            registerBtReceiver();
            if (bluetoothAdapter != null) {
                bluetoothAdapter.getProfileProxy(
                        context,
                        bluetoothProfileListener,
                        BluetoothProfile.HEADSET
                );
            }
        }
    }

    private void disableBluetoothEvents() {
        if (null != bluetoothProfile && bluetoothAdapter != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothProfile);
        }
        unregisterBtReceiver();
        /* force a shutdown of bluetooth: when a call comes in, the handler is not invoked by system */
        Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED);
        btStatusReceiver.onReceive(context, intent);
    }

    private void startBluetoothSco() {
        try {
            audioManager.startBluetoothSco();
        } catch (NullPointerException npex) {
        }
    }

    private void stopBluetoothSco() {
        try {
            audioManager.stopBluetoothSco();
        } catch (NullPointerException npex) {
        }
    }

    //20200527 iwarrior 채점 초기화
    public void initScore(){
        //20200523 iwarrior 채점 변수 초기화
        mScore = mScoreCount = 0;
    }

    //20200523 iwarrior 채점 결과를 반환한다.
    public int getScore(){
        int score = 0;

        if(mScoreCount != 0 ){
            score = (mScore*100)/mScoreCount;
        }
        return score;
    }

    //20200618 iwarrior 볼륨 다운
    public boolean volumeDown(){
        try {
            mVol -= 0.1;
            audioTrack.setVolume(mVol);
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            return true;
        }catch (Exception e){
            e.printStackTrace();
            return false;
        }
    }

    //20200609 iwarrior 볼륨 업
    public boolean volumeUp(){
        try {
            mVol += 0.1;
            audioTrack.setVolume(mVol);
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            return true;
        }catch (Exception e){
            e.printStackTrace();
            return false;
        }
    }


    public void CopyWaveFile(String inFilename,String outFilename, int nChannels){
        final int RECORDER_SAMPLERATE = 44100;
        int RECORDER_NUMCH = 1;
        int RECORDER_CHANNELS = android.media.AudioFormat.CHANNEL_IN_MONO;
        final int RECORDER_AUDIO_ENCODING = android.media.AudioFormat.ENCODING_PCM_16BIT;
        //voice 1 mr 2
        if(nChannels == 1){
            RECORDER_NUMCH = 1;
            RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
        }else{
            RECORDER_NUMCH = 2;
            RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_STEREO;
        }

        final int bufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE,RECORDER_CHANNELS,RECORDER_AUDIO_ENCODING)*3;
        FileInputStream in = null;
        FileOutputStream out = null;
        long totalAudioLen = 0;
        long totalDataLen = totalAudioLen + 36;
        long longSampleRate = RECORDER_SAMPLERATE;
        int channels = RECORDER_NUMCH;
        long byteRate = 16 * RECORDER_SAMPLERATE * channels/8;

        byte[] data = new byte[bufferSize];

        try {
            in = new FileInputStream(inFilename);
            out = new FileOutputStream(outFilename);
            totalAudioLen = in.getChannel().size();

//            totalAudioLen = in.available();
//            totalAudioLen = nWrittenLen;
//            totalAudioLen = nTotLen;
            totalDataLen = totalAudioLen + 36;

            Log.d ("WavEncoding", "File size: " + totalDataLen);

            WriteWaveFileHeader(out, totalAudioLen, totalDataLen,
                    longSampleRate, channels, byteRate);
            if(nChannels ==1){
                //  byte[] bytes=new byte[data.length];
                while(in.read(data) != -1) {
                  /*  short[] shorts=new short[data.length/2];
                    ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(shorts);
                    ByteBuffer byteBuffer=ByteBuffer.allocate(shorts.length*2);
                    for(int i=0;i<shorts.length;i++){
                        short a=(short)(shorts[i]*(short)1.2);
                        byteBuffer.putShort(a);
                    }
                    bytes=byteBuffer.array();*/
                    out.write(data);
                }
            }else{
                while(in.read(data) != -1) {
                    out.write(data);
                }
            }


            in.close();
            out.close();

//
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void WriteWaveFileHeader(
            FileOutputStream out, long totalAudioLen,
            long totalDataLen, long longSampleRate, int channels,
            long byteRate) throws IOException
    {
        byte[] header = new byte[44];

        header[0] = 'R';  // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';  // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;  // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1;  // format = 1
        header[21] = 0;
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * 16 / 8);  // block align
        header[33] = 0;
        header[34] = 16;  // bits per sample
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);
        out.write(header, 0, 44);
    }



}