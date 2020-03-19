# VibroRecorder
App for recording .wav audio and accelerometer output. It was used to gather data for academic research.


## Permissions
###### Requires following permissions: 
- Record Audio 
- Write External Storage

###### Uses following features: 
- Acceleration Sensor

## Output
"VibroRecorder" folder is created in phone's memory with 2 subfolders:
- "acceleration" for storing .txt accelerometer output
- "audio" for storing .wav audio files

###### .txt output
Acceleration data is sampled from XYZ axes and written into columns. The first and the last line of the file contains a timestamp, which can be used to calculate sampling and generate time axis. 

###### .wav output
Audio is recorded into 2-channel PCM .wav 44.1kHz/16-bit format. 

## Credits
- **Maurycy Chronowski** - *initial code* - [Drzwioddomu](https://github.com/Drzwioddomu)
- **Kiranshastry** - *icon* -[@Flaticon](https://www.flaticon.com/authors/kiranshastry)

## License 
Distributed under the MIT License - see the [LICENSE.md](https://github.com/Drzwioddomu/VibroRecorder/blob/master/LICENSE.md) file for details.

