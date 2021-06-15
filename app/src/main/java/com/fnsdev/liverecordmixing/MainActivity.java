package com.fnsdev.liverecordmixing;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

public class MainActivity extends AppCompatActivity {

    String[] permissionCkeck={
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        recVoice=RecVoice.getInstance(MainActivity.this);
        getPermission();
        initMember();
        songClickEvent();
        recPlayEvent();

    }

    Button btnPlayStart,btnPlayStop;

    Button btninit,btnSongStart,btnSongPause,btnSongStop,btnRecStop;

    public void initMember(){
        //녹음된 음성 관련
        btnPlayStart=(Button)findViewById(R.id.btnPlayStart);
        btnPlayStop=(Button)findViewById(R.id.btnPlayStop);

        //노래 재생 관련
        btninit=(Button)findViewById(R.id.btninit);
        btnSongStart=(Button)findViewById(R.id.btnSongStart);
        btnSongPause=(Button)findViewById(R.id.btnSongPause);
        btnSongStop=(Button)findViewById(R.id.btnSongStop);
        btnRecStop=(Button)findViewById(R.id.btnRecStop);

    }

    SongCtrl songCtrl;
    RecVoice recVoice;
    String SONG_FILE_PATH;

    public void songClickEvent(){

        btninit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                songCtrl=SongCtrl.getInstance();
                SONG_FILE_PATH=getApplicationContext().getFilesDir().getPath().toString();
                Log.d("도원",SONG_FILE_PATH+"");
                songCtrl.initBass();
                songCtrl.songInit(SONG_FILE_PATH+"/song.mp3");


                recVoice.initCapturer();
                recVoice.startCapturer();

                btnSongStart.setEnabled(true);
                btnRecStop.setEnabled(true);

            }
        });

        btnSongStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                songCtrl.songPlay();
                btnSongPause.setEnabled(true);
                btnSongStop.setEnabled(true);
            }
        });

        btnSongPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                songCtrl.songPause();
            }
        });

        btnSongStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                songCtrl.songStop();
            }
        });

        btnRecStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recVoice.stopCapturer();
                recVoice.destroyCapturer();
                btnPlayStart.setEnabled(true);
                btnPlayStop.setEnabled(true);
            }
        });

    }

    public void recPlayEvent(){
        btnPlayStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });

        btnPlayStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });

    }

    public boolean isPlaySong(){
        if(songCtrl.isPlaying()==PlayState.PLAYSTATE_PLAY){
            return true;
        }else{
            return false;
        }

    }

    public boolean hsaPermission(Context context,String[] permissionCkeck){

        if(context != null && permissionCkeck != null){
            for(String permissions:permissionCkeck){
                if(ActivityCompat.checkSelfPermission(context,permissions) != PackageManager.PERMISSION_GRANTED){
                    return false;
                }

            }
        }

        return true;
    }

    public void getPermission(){
        ActivityCompat.requestPermissions(this,permissionCkeck,1000);
    }


}