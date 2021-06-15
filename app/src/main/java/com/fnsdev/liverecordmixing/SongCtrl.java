package com.fnsdev.liverecordmixing;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.util.Log;

import com.un4seen.bass.BASS;
import com.un4seen.bass.BASS_FX;
import com.un4seen.bass.BASSenc;
import com.un4seen.bass.BASSmix;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;


enum PlayState{
    PLAYSTATE_STOP,
    PLAYSTATE_PLAY_REQ,
    PLAYSTATE_READY2PLAY,
    PLAYSTATE_PLAY,
    PLAYSTATE_RECORD,
    PLAYSTATE_SCORE,
    PLAYSTATE_PAUSE
}

public class SongCtrl {

    PlayState m_nPlayState=PlayState.PLAYSTATE_STOP;

    private SongCtrl(){

    }
    private  static SongCtrl instance;
    public static SongCtrl getInstance(){

        if(instance==null){
            instance=new SongCtrl();
        }

        return instance;
    }

    public boolean initBass(){
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_UPDATEPERIOD, 10);
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_DEV_BUFFER,100);

        if (!BASS.BASS_Init(1, 44100, BASS.BASS_DEVICE_SPEAKERS)) {
            int nResult= BASS.BASS_ErrorGetCode();
            Log.d("도원에러", "BASS_StreamCreateFile = " + String.valueOf(nResult));
            return false;
        }
        return true;
    }

    String songPath;
    String encPathPcm;
    String encPathWav;
    int songChan=0,songMixChan=0;
    int sendChan=0;

    public int songInit(String file){
        songPath=file;
        encPathPcm=file.replace("song.mp3","ecnFile.pcm");
        encPathWav=encPathPcm.replace(".pcm",".wav");
        BASS.BASS_StreamFree(songChan); // free the old stream
        BASS.BASS_StreamFree(songMixChan);
        BASS.BASS_StreamFree(sendChan);

        sendChan=BASS.BASS_StreamCreateFile(file,0,0,BASS.BASS_STREAM_DECODE);

        songChan=BASS.BASS_StreamCreateFile(file,0,0,BASS.BASS_STREAM_DECODE);
        songChan=BASS_FX.BASS_FX_TempoCreate(songChan,BASS.BASS_STREAM_DECODE);

        songMixChan=BASSmix.BASS_Mixer_StreamCreate(44100,2,BASS.BASS_STREAM_AUTOFREE);
        BASS_FX.BASS_FX_TempoCreate(songMixChan, BASS.BASS_STREAM_DECODE );
        BASSmix.BASS_Mixer_StreamAddChannel(songMixChan,songChan, BASS.BASS_STREAM_AUTOFREE);
        BASSenc.BASS_Encode_Start(songMixChan,encPathPcm,BASSenc.BASS_ENCODE_PCM,null,null);
        if(songChan==0){
            int nResult=BASS.BASS_ErrorGetCode();
            Log.d("도원에러","songinit : "+nResult);
        }


        return songMixChan;
    }

    public void songPlay() {
        boolean bPlay = BASS.BASS_ChannelPlay(songMixChan, false);
        if (bPlay == true) {
            m_nPlayState = PlayState.PLAYSTATE_PLAY;
        }else{
            int nResult=BASS.BASS_ErrorGetCode();
            Log.d("도원에러","songinit : "+nResult);
        }
    }

    public void songPause() {
        BASS.BASS_ChannelPause(songMixChan);
        m_nPlayState = PlayState.PLAYSTATE_PAUSE;
    }

    public void songStop() {
        BASS.BASS_ChannelStop(songMixChan);
        BASS.BASS_StreamFree(songMixChan);

        CopyWaveFile(encPathPcm,encPathWav,2);
        m_nPlayState = PlayState.PLAYSTATE_STOP;
    }

    public PlayState isPlaying(){

        return m_nPlayState;
    }

    public long getLength(){
        return BASS.BASS_ChannelGetLength(sendChan,
                BASS.BASS_POS_BYTE);
    }

    public int getPlayData(ByteBuffer byteBuffer, int nLen){
        int bi = 0;
        if(sendChan == 0){
            return 0;
        }
        if(byteBuffer.capacity() >= nLen){
            bi = BASS.BASS_ChannelGetData(sendChan, byteBuffer, nLen);
        }
        return bi;
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
