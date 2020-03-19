package com.example.vibrorecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Log
import kotlinx.android.synthetic.main.activity_main.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "RecorderLogTag"

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor
    private lateinit var timerHandler: Handler
    private lateinit var accelOutput: File
    private lateinit var micOutput: File
    private lateinit var micTemporaryOutput: File
    private var micRecorder: AudioRecord? = null

    //output dirs
    private val accelOutputFolder = "VibroRecorder/acceleration"
    private val micOutputFolder = "VibroRecorder/audio"

    //recorder setup
    private var recordingState = false
    private val recorderBPS = 16
    private val wavExtension = ".wav"
    private val recorderSampleRate = 44100
    private val recorderChannels = AudioFormat.CHANNEL_IN_STEREO
    private val recorderEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(recorderSampleRate, recorderChannels, recorderEncoding)*3
    private var recorderThread: Thread? = null

    //timer vars
    private var startTime: Long = 0
    private var millisecondsTime: Long = 0
    private var timeBuffer: Long = 0
    private var updateTime = 0L
    private var seconds: Int = 0
    private var minutes:Int = 0
    private var milliseconds: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupPermissions()

        //initialization
        timerHandler = Handler()

        //sensor setup
        this.sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            this.accelerometer = it
        }

        //access directories
        if (isExternalStorageWritable()){
            accelOutput = getExternalStorageDir(accelOutputFolder)
            micOutput = getExternalStorageDir(micOutputFolder)
            micTemporaryOutput = getFilename(micOutput, "recorder_temp", ".raw")
        }

        //button listeners
        recButton.setOnClickListener {
            if (recordingState) {

                //stop recording
                timerHandler.removeCallbacks(timerRunnable)
                timeView.text = resources.getString(R.string.timeview_default_value)
                writeLineToFile(accelOutput, (getCurrentTimeStamp() + "\n"))
                sensorManager.unregisterListener(this)
                recordingState = false
                stopRecording()

                //reset recorder
                accelOutput = getExternalStorageDir(accelOutputFolder)
                micOutput = getExternalStorageDir(micOutputFolder)
                micRecorder = null

            } else {

                //start recording
                startTime = SystemClock.uptimeMillis()
                timerHandler.post(timerRunnable)

                val accelOutputFilename = getCurrentTimeStamp(format = "yyyy-MM-dd") + " " + patientID.text.toString()
                accelOutput = getFilename(accelOutput, accelOutputFilename, ".txt")
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
                writeLineToFile(accelOutput, (getCurrentTimeStamp() + "\n"))

                micRecorder = if (Build.VERSION.SDK_INT >= 24){
                    AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,
                        recorderSampleRate, recorderChannels, recorderEncoding, bufferSize)
                } else{
                    AudioRecord(MediaRecorder.AudioSource.MIC,
                        recorderSampleRate, recorderChannels, recorderEncoding, bufferSize)
                }
                micOutput = getFilename(micOutput, (getCurrentTimeStamp(format = "yyyy-MM-dd") + " " + patientID.text.toString()), wavExtension)
                startRecording()

                recordingState = true
            }
        }
    }

    //permission setup
    private fun setupPermissions(){
        val writeExternalPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val recordAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (writeExternalPermission != PackageManager.PERMISSION_GRANTED || recordAudioPermission != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            101 -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Permission has been denied by user.")
                } else {
                    Log.i(TAG, "Permission has been granted by user.")
                }
            }
        }
    }

    //recorder
    private fun startRecording() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        try {
            if (micRecorder?.state == 1) {
                micRecorder?.startRecording()
            }

            recorderThread = Thread(Runnable {
                writeAudioDataToFile()
            }, "AudioRecorder Thread")
            recorderThread?.start()

        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    private fun stopRecording(){
        if (micRecorder?.state == 1) {
            micRecorder?.stop()
        }
        micRecorder?.release()
        recorderThread?.interrupt()
        recorderThread = null
        copyWaveFile(micTemporaryOutput.absolutePath, micOutput.absolutePath)
        deleteTempFile()
    }
    private fun writeWaveFileHeader(out: FileOutputStream, totalAudioLen: Long, totalDataLen: Long, longSampleRate: Long, channels: Int) {
        val header = ByteArray(44)
        val byteRate: Long = longSampleRate * recorderBPS.toLong() * channels / 8

        header[0] = 'R'.toByte()  // RIFF/WAVE header
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte()  // 'fmt ' chunk
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16  // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1  // format = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = (longSampleRate shr 8 and 0xff).toByte()
        header[26] = (longSampleRate shr 16 and 0xff).toByte()
        header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (2 * 16 / 8).toByte()  // block align
        header[33] = 0
        header[34] = recorderBPS.toByte()  // bits per sample
        header[35] = 0
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        out.write(header, 0, 44)
    }
    private fun writeAudioDataToFile() {
        var data = ByteArray(bufferSize)
        var read: Int
        val path = micTemporaryOutput
        var os: FileOutputStream? = null

        try {
            os = FileOutputStream(path)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }

        if (os != null) {
            while (recordingState) {
                read = micRecorder!!.read(data, 0, bufferSize)
                if (read != AudioRecord.ERROR_INVALID_OPERATION) {
                    try {
                        os.write(data)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
            try {
                os.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
    private fun copyWaveFile(inFilename: String, outFilename: String){
        var input: FileInputStream
        var output: FileOutputStream
        var totalAudioLen: Long
        var totalDataLen: Long
        var longSampleRate: Long = recorderSampleRate.toLong()
        var channels = 2

        var data = ByteArray(bufferSize)

        try {
            input = FileInputStream(inFilename)
            output = FileOutputStream(outFilename)
            totalAudioLen = input.channel.size()
            totalDataLen = totalAudioLen + 36

            writeWaveFileHeader(output, totalAudioLen, totalDataLen, longSampleRate, channels)

            while (input.read(data) != -1)  {
                output.write(data)
            }

            input.close()
            output.close()
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    private fun deleteTempFile() {
        micTemporaryOutput.delete()
    }

    //sensor
    override fun onSensorChanged(event: SensorEvent?) {
        val x = event?.values?.get(0)
        val y = event?.values?.get(1)
        val z = event?.values?.get(2)
        accelViewX.text = String.format("%.2f", x)
        accelViewY.text = String.format("%.2f", y)
        accelViewZ.text = String.format("%.2f", z)
        if (recordingState) {
            val accelOutputString = x.toString() + " " + y.toString() + " " + z.toString() + "\n"
            writeLineToFile(accelOutput, accelOutputString)
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    //timer
    private fun fitTimeToString(timeValue: Int): String {
        var fitted = timeValue.toString()
        if (fitted.length < 2) {
            fitted = "0$fitted"
        }
        return fitted
    }
    private var timerRunnable: Runnable = object : Runnable {
        override fun run() {
            millisecondsTime = SystemClock.uptimeMillis() - startTime
            updateTime = timeBuffer + millisecondsTime
            seconds = (updateTime / 1000).toInt()
            minutes = seconds / 60
            seconds %= 60
            milliseconds = ((updateTime % 1000)/10).toInt()
            timeView.text = fitTimeToString(minutes) + ":" + fitTimeToString(seconds) + ":" + fitTimeToString(milliseconds)
            timerHandler.post(this)
        }
    }

    //files
    private fun isExternalStorageWritable() : Boolean{
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }
    private fun getExternalStorageDir(folderName: String): File {
        // Get the directory for the user's public pictures directory.
        val file = File(Environment.getExternalStorageDirectory().absolutePath, folderName)
        if (!file.mkdirs()) {
            Log.e(TAG, "Directory not created")
        }
        return file
    }
    private fun getCurrentTimeStamp(format: String = "yyyy-MM-dd HH:mm:ss:SSS"): String {
        val dateFormat: SimpleDateFormat
        try {
            dateFormat = SimpleDateFormat(format)
        } catch (e: Exception) {
            e.printStackTrace()

            return ""
        }
        return dateFormat.format(Date())
    }
    private fun getFilename(path: File, filename: String, extension: String): File{
        var i = 0
        var resultFilename = filename + extension
        var f = File(path, resultFilename)
        while (f.exists()) {
            i++
            resultFilename = "$filename($i)$extension"
            f = File(path, resultFilename)
        }
        return f
    }
    private fun writeLineToFile(file: File, data: String) {
        try {
            val fw = FileWriter(file, true)
            fw.write(data)
            fw.close()
        } catch (e: IOException) {
        }
    }
}
