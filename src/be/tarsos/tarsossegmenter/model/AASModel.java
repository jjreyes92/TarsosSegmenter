package be.tarsos.tarsossegmenter.model;

import be.tarsos.transcoder.ffmpeg.EncoderException;
import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.AutoCorrelation;
import be.tarsos.dsp.ConstantQ;
import be.tarsos.dsp.mfcc.MFCC;
import be.tarsos.tarsossegmenter.controller.listeners.AASModelListener;
import be.tarsos.tarsossegmenter.controller.listeners.AudioFileListener;
import be.tarsos.tarsossegmenter.gui.BackgroundTask;
import be.tarsos.tarsossegmenter.gui.ProgressDialog;
import be.tarsos.tarsossegmenter.gui.TarsosSegmenterGui;
import be.tarsos.tarsossegmenter.model.player.Player;
import be.tarsos.tarsossegmenter.model.segmentation.Segmentation;
import be.tarsos.tarsossegmenter.model.structure.StructureDetection;
import be.tarsos.tarsossegmenter.util.NoveltyScore;
import be.tarsos.tarsossegmenter.util.TimeUnit;
import be.tarsos.tarsossegmenter.util.configuration.ConfKey;
import be.tarsos.tarsossegmenter.util.configuration.Configuration;
import be.tarsos.tarsossegmenter.util.io.FileUtils;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.JOptionPane;
import javax.swing.event.EventListenerList;

/**
 * <p>
 * The main graphical user interface. This contains all other GUI's.
 * </p>
 * 
 * @author Thomas Stubbe
 * 
 */

public class AASModel {

	public static final int FEATURE_ORIGIN_MFCC = 0;
	public static final int FEATURE_ORIGIN_CQT = 1;
	public static final int FEATURE_ORIGIN_AUTOCORRELATIE = 2;

	public static final float CQT_THRESHOLD = 0.0008f;
	public static float MAX_SCALE_VALUE = 1000;
	public static final int MACRO_LEVEL = 0;
	public static final int MESO_LEVEL = 1;
	public static final int MICRO_LEVEL = 2;
	private boolean useMFCC;
	private boolean useAutoCorrelation;
	private boolean useCQT;
	private AudioFile audioFile;
	private int amountOfFrames;
	private int frameSize; // frameSize
	private int overlapping;
	private float sampleRate;
	private float lowerFilterFreq;
	private float upperFilterFreq;
	static int samplesPerPixel;
	private int mfccCoef;
	private int melfilters;
	private int cqtBins;
	private AudioDispatcher ad;
	private float[][] mfccs;
	private float[][] cqtcs;
	private float[][] autoCorrelationcs;
	private float[][] similarityMatrix;
	private float[][] resultMatrix;
	private float[][] noveltyScores;
	private EventListenerList modelListenerList = new EventListenerList();
	private EventListenerList audioFileListenerList = new EventListenerList();
	private boolean macroEnabled;
	private boolean mesoEnabled;
	private boolean microEnabled;
	private static AASModel instance;
	private boolean guiEnabled;
	private boolean onlyStructureDetection;
	private Segmentation segmentation;
	private boolean done;

	public static AASModel getInstance() {
		if (instance == null) {
			instance = new AASModel();
		}
		return instance;
	}

	private AASModel() {
		Configuration.checkForConfigurationAndWriteDefaults();
        Configuration.configureDirectories();
		guiEnabled = false;
		onlyStructureDetection = false;
		segmentation = new Segmentation();
		loadConfiguration();
	}

	public boolean isCalculated() {
		return !this.segmentation.isEmpty();
	}

	public void setGuiEnabled(boolean value) {
		this.guiEnabled = value;
	}

	public final void loadConfiguration() {
		boolean oldUseMFCC = useMFCC;
		boolean oldUseAutoCorrelation = useAutoCorrelation;
		boolean oldUseCQT = useCQT;
		int oldFrameSize = frameSize;
		int oldOverlapping = overlapping;
		int oldCqtBins = cqtBins;
		int oldMfccCoef = mfccCoef;
		int oldMelfilters = melfilters;
		float oldLowerFilterFreq = lowerFilterFreq;
		float oldUpperFilterFreq = upperFilterFreq;

		useMFCC = Configuration.getBoolean(ConfKey.enable_mfcc);
		useAutoCorrelation = Configuration
				.getBoolean(ConfKey.enable_autocorrelation);
		useCQT = Configuration.getBoolean(ConfKey.enable_cqt);
		frameSize = Configuration.getInt(ConfKey.framesize);
		overlapping = Configuration.getInt(ConfKey.overlapping);
		cqtBins = Configuration.getInt(ConfKey.cqt_bins);
		mfccCoef = Configuration.getInt(ConfKey.mfcc_coef);
		macroEnabled = Configuration.getBoolean(ConfKey.enable_macro);
		mesoEnabled = Configuration.getBoolean(ConfKey.enable_meso);
		microEnabled = Configuration.getBoolean(ConfKey.enable_micro);
		melfilters = Configuration.getInt(ConfKey.mfcc_melfilters);
		lowerFilterFreq = Configuration.getInt(ConfKey.lowfilterfreq);
		upperFilterFreq = Configuration.getInt(ConfKey.upperfilterfreq);

		if (onlyStructureDetection == true && oldUseMFCC == useMFCC
				&& oldUseAutoCorrelation == useAutoCorrelation
				&& oldUseCQT == useCQT && oldFrameSize == frameSize
				&& oldOverlapping == overlapping && oldCqtBins == cqtBins
				&& oldMfccCoef == mfccCoef && oldMelfilters == melfilters
				&& oldLowerFilterFreq == lowerFilterFreq
				&& oldUpperFilterFreq == upperFilterFreq) {
			this.onlyStructureDetection = true;
		} else {
			this.onlyStructureDetection = false;
		}

		if (audioFile != null) {
			amountOfFrames = audioFile.fileFormat().getFrameLength()
					/ (frameSize - overlapping);
			samplesPerPixel = (int) Math.pow(
					2,
					(int) Math.floor(Math.log(audioFile.fileFormat()
							.getFrameLength() / 800) / Math.log(2)));
		}
	}

	public void calculateWithDefaults(AudioFile file, int lowerFilterFreq,
			int upperFilterFreq) {
		done = false;
		this.onCalculationStarted();
		if (audioFile == null
				|| !this.audioFile.transcodedPath().equals(
						file.transcodedPath().toString())) {
			this.segmentation.clearAll();
			this.audioFile = file;
			onAudioFileChange();
		}
		if (!isCalculated()) {
			this.useMFCC = true;
			this.useAutoCorrelation = false;
			this.useCQT = false;
			this.frameSize = 4096;
			this.overlapping = 1024;
			this.cqtBins = 0;
			this.mfccCoef = 40;
			this.macroEnabled = true;
			this.mesoEnabled = true;
			this.microEnabled = true;
			this.melfilters = 40;
			this.lowerFilterFreq = lowerFilterFreq;
			this.upperFilterFreq = upperFilterFreq;

			Configuration.set(ConfKey.enable_mfcc, useMFCC);
			Configuration.set(ConfKey.enable_autocorrelation,
					useAutoCorrelation);
			Configuration.set(ConfKey.enable_cqt, useCQT);
			Configuration.set(ConfKey.framesize, frameSize);
			Configuration.set(ConfKey.overlapping, overlapping);
			Configuration.set(ConfKey.cqt_bins, cqtBins);
			Configuration.set(ConfKey.mfcc_coef, mfccCoef);
			Configuration.set(ConfKey.enable_macro, macroEnabled);
			Configuration.set(ConfKey.enable_meso, mesoEnabled);
			Configuration.set(ConfKey.enable_micro, microEnabled);
			Configuration.set(ConfKey.mfcc_melfilters, melfilters);
			Configuration.set(ConfKey.lowfilterfreq, lowerFilterFreq);
			Configuration.set(ConfKey.upperfilterfreq, upperFilterFreq);

			try {
				File f = new File(audioFile.transcodedPath());
				ad = AudioDispatcher.fromFile(f, audioFile.fileFormat()
						.getFrameLength(), 0);
				ad.setStepSizeAndOverlap(frameSize, overlapping);
			} catch (Exception e) {
				JOptionPane
						.showMessageDialog(
								null,
								"Could not transcode audiofile: make sure it is an audiofile and that you have access/rights to the file",
								"Error", JOptionPane.ERROR_MESSAGE);
			}
			// double amountOfSamples =
			// audioFile.fileFormat().getFormat().getSampleRate()*ad.durationInSeconds();
			float durationInFrames = ((float) (ad.durationInFrames() - frameSize) / (float) (frameSize
					- overlapping + 1)) + 1;
			this.amountOfFrames = (int) Math.ceil(durationInFrames);
			final MFCC mfccAD;

			mfccAD = new MFCC(this.frameSize, audioFile.fileFormat()
					.getFormat().getSampleRate(), this.melfilters,
					this.mfccCoef, this.lowerFilterFreq, this.upperFilterFreq);
			ad.addAudioProcessor(mfccAD);
			this.mfccs = new float[this.amountOfFrames][];

			ad.addAudioProcessor(new AudioProcessor() {
				private int count = 0;

				@Override
				public boolean process(AudioEvent audioEvent) {
					AASModel.getInstance().addFeaturesToFrame(count,
							AASModel.FEATURE_ORIGIN_MFCC, mfccAD.getMFCC());
					count++;
					return true;
				}

				@Override
				public void processingFinished() {
					done = true;
				}
			});
			ad.run();
			while (!done) {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			ad.removeAudioProcessor(mfccAD);
			constructSelfSimilarityMatrix();
			resultMatrix = new float[similarityMatrix.length][];
			for (int i = 0; i < similarityMatrix.length; i++) {
				resultMatrix[i] = new float[similarityMatrix[i].length];
				System.arraycopy(similarityMatrix[i], 0,
						resultMatrix[i], 0, similarityMatrix[i].length);
			}
			segmentation.clearAll();
			noveltyScores = NoveltyScore.calculateScore(
					similarityMatrix,
					audioFile.getLengthInMilliSeconds());
			StructureDetection sd = new StructureDetection(audioFile
					.getLengthInMilliSeconds() / 1000f, resultMatrix,
					MAX_SCALE_VALUE);
			sd.preProcessing();
			sd.run();
			sd = null;
			// System.gc();
		}
		this.onCalculationEnd();
	}

	public void calculate() throws java.lang.OutOfMemoryError {
		if (frameSize <= 8192 && audioFile.getLengthIn(TimeUnit.MINUTES) > 16) {
			int result = JOptionPane
					.showConfirmDialog(
							TarsosSegmenterGui.getInstance(),
							"Analysing this audiofile with a framesize <= 8192 would be very intensive for the CPU and memory.\nWould you like to set the framesize to 16384?",
							"Warning: Intensive task",
							JOptionPane.YES_NO_CANCEL_OPTION);
			if (result != JOptionPane.CANCEL_OPTION) {
				if (result == JOptionPane.YES_OPTION) {
					Configuration.set(ConfKey.framesize, 16384);
					loadConfiguration();
				}
			} else {
				return;
			}
		} else if (frameSize <= 4096
				&& audioFile.getLengthIn(TimeUnit.MINUTES) > 12) {
			int result = JOptionPane
					.showConfirmDialog(
							TarsosSegmenterGui.getInstance(),
							"Analysing this audiofile with a framesize <= 4096 would be very intensive for the CPU and memory.\nWould you like to set the framesize to 8192?",
							"Warning: Intensive task",
							JOptionPane.YES_NO_CANCEL_OPTION);
			if (result != JOptionPane.CANCEL_OPTION) {
				if (result == JOptionPane.YES_OPTION) {
					Configuration.set(ConfKey.framesize, 8192);
					loadConfiguration();
				}
			} else {
				return;
			}
		} else if (frameSize <= 2048
				&& audioFile.getLengthIn(TimeUnit.SECONDS) > 360) {
			int result = JOptionPane
					.showConfirmDialog(
							TarsosSegmenterGui.getInstance(),
							"Analysing this audiofile with a framesize <= 2048 would be very intensive for the CPU and memory.\nWould you like to set the framesize to 4096?",
							"Warning: Intensive task",
							JOptionPane.YES_NO_CANCEL_OPTION);
			if (result != JOptionPane.CANCEL_OPTION) {
				if (result == JOptionPane.YES_OPTION) {
					Configuration.set(ConfKey.framesize, 4096);
					loadConfiguration();
				}
			} else {
				return;
			}
		}

		if (!onlyStructureDetection) {
			try {
				File file = new File(audioFile.transcodedPath());
				ad = AudioDispatcher.fromFile(file, audioFile.fileFormat()
						.getFrameLength(), 0);
				ad.setStepSizeAndOverlap(frameSize, overlapping);
			} catch (Exception e) {
				JOptionPane
						.showMessageDialog(
								TarsosSegmenterGui.getInstance(),
								"Could not transcode audiofile: make sure it is an audiofile and that you have access/rights to the file",
								"Error", JOptionPane.ERROR_MESSAGE);
			}
			float durationInFrames = ((float) ad.durationInFrames() / (float) (frameSize
					- overlapping + 1));
			this.amountOfFrames = (int) Math.ceil(durationInFrames);
			final MFCC mfccAD;
			final ConstantQ cqtAD;
			final AutoCorrelation acAD;

			if (useMFCC) {
				mfccAD = new MFCC(this.frameSize, this.sampleRate,
						this.melfilters, this.mfccCoef, this.lowerFilterFreq,
						this.upperFilterFreq);
				ad.addAudioProcessor(mfccAD);
				this.mfccs = new float[this.amountOfFrames][];
			} else {
				mfccAD = null;
			}
			if (useAutoCorrelation) {
				acAD = new AutoCorrelation();
				ad.addAudioProcessor(acAD);
				this.autoCorrelationcs = new float[this.amountOfFrames][];
			} else {
				acAD = null;
			}
			if (useCQT) {
				cqtAD = new ConstantQ(sampleRate, lowerFilterFreq,
						upperFilterFreq, cqtBins);
				ad.addAudioProcessor(cqtAD);
				this.cqtcs = new float[this.amountOfFrames][];
			} else {
				cqtAD = null;
			}

			ad.addAudioProcessor(new AudioProcessor() {
				private int count = 0;

				@Override
				public boolean process(AudioEvent audioEvent) {

					if (useMFCC) {
						AASModel.getInstance().addFeaturesToFrame(count,
								AASModel.FEATURE_ORIGIN_MFCC, mfccAD.getMFCC());
					}
					if (useAutoCorrelation) {
						AASModel.getInstance().addFeaturesToFrame(count,
								AASModel.FEATURE_ORIGIN_AUTOCORRELATIE,
								acAD.getValues());
					}
					if (useCQT) {
						AASModel.getInstance().addFeaturesToFrame(count,
								AASModel.FEATURE_ORIGIN_CQT,
								cqtAD.getMagnitudes());
					}
					count++;
					return true;
				}

				@Override
				public void processingFinished() {
				}
			});
			ad.run();

			if (useMFCC) {
				// mfccs = mfccAD.getMFCC();
				ad.removeAudioProcessor(mfccAD);
			}
			if (useAutoCorrelation) {
				// autoCorrelationcs = acAD.getValues();
				ad.removeAudioProcessor(acAD);
			}
			if (useCQT) {
				// cqtcs = cqtAD.getValues();
				ad.removeAudioProcessor(cqtAD);
			}
			if (guiEnabled) {
				constructSelfSimilarityMatrix();
			}

			// mfccs = null;
			// autoCorrelationcs = null;
			// cqtcs = null;

			System.gc();

		}
		resultMatrix = new float[similarityMatrix.length][];
		for (int i = 0; i < similarityMatrix.length; i++) {
			resultMatrix[i] = new float[similarityMatrix[i].length];
			System.arraycopy(similarityMatrix[i], 0, resultMatrix[i], 0,
					similarityMatrix[i].length);
		}
		if (this.macroEnabled) {
			segmentation.clearAll();
		} else if (this.mesoEnabled) {
			segmentation.clearMesoAndMicro();
		} else if (this.microEnabled) {
			segmentation.clearMicro();
		}
		noveltyScores = NoveltyScore.calculateScore(similarityMatrix,
				audioFile.getLengthInMilliSeconds());
		StructureDetection sd = new StructureDetection(
				audioFile.getLengthInMilliSeconds() / 1000f, resultMatrix,
				MAX_SCALE_VALUE);
		sd.preProcessing();

		if (macroEnabled || mesoEnabled || microEnabled) {
			sd.run();
		}
		sd = null;
		System.gc();
	}

	public AudioFile getAudioFile() {
		return audioFile;
	}

	public float[][] getSimilarityMatrix() {
		return resultMatrix;
	}

	public float[][] getInitialSimilarityMatrix() {
		return similarityMatrix;
	}

	public int getOverlapping() {
		return overlapping / 1000;
	}

	public float getSampleRate() {
		return sampleRate;
	}

	public int getSamplesPerFrame() {
		return frameSize;
	}

	public void addModelListener(AASModelListener listener) {
		modelListenerList.add(AASModelListener.class, listener);
	}

	// This methods allows classes to unregister for MyEvents
	public void removeModelListener(AASModelListener listener) {
		modelListenerList.remove(AASModelListener.class, listener);
	}

	private void onCalculationStarted() {
		segmentation.clearAll();
		Object[] listeners = modelListenerList.getListenerList();
		// Each listener occupies two elements - the first is the listener class
		// and the second is the listener instance
		for (int i = 0; i < listeners.length; i += 2) {
			if (listeners[i] == AASModelListener.class) {
				((AASModelListener) listeners[i + 1]).calculationStarted();
			}
		}
	}

	private void onCalculationEnd() {
		onlyStructureDetection = true;
		// System.out.print("END");
		Object[] listeners = modelListenerList.getListenerList();
		// Each listener occupies two elements - the first is the listener class
		// and the second is the listener instance
		for (int i = 0; i < listeners.length; i += 2) {
			if (listeners[i] == AASModelListener.class) {
				((AASModelListener) listeners[i + 1]).calculationDone();
			}
		}
	}

	public void constructSelfSimilarityMatrix() {
		float maxMFCC = Float.MIN_VALUE;
		float minMFCC = Float.MAX_VALUE;
		float maxAC = Float.MIN_VALUE;
		float minAC = Float.MAX_VALUE;
		float maxCQT = Float.MIN_VALUE;
		float minCQT = Float.MAX_VALUE;

		int size = amountOfFrames;

		float[][] mfcSimilarityMatrix = null;
		float[][] acSimilarityMatrix = null;
		float[][] cqtSimilarityMatrix = null;
		similarityMatrix = null;

		if (useMFCC) {
			mfcSimilarityMatrix = new float[size][];
			for (int i = 0; i < size; i++) {
				mfcSimilarityMatrix[i] = new float[i + 1];
			}
		}
		if (useAutoCorrelation) {
			acSimilarityMatrix = new float[size][];
			for (int i = 0; i < size; i++) {
				acSimilarityMatrix[i] = new float[i + 1];
			}
		}
		if (useCQT) {
			cqtSimilarityMatrix = new float[size][];
			for (int i = 0; i < size; i++) {
				cqtSimilarityMatrix[i] = new float[i + 1];
			}
		}

		similarityMatrix = new float[size][];
		for (int i = 0; i < size; i++) {
			similarityMatrix[i] = new float[i + 1];
		}

		// Average bij meerdere COEF bepalen
		// Min en Max voor de range bepalen
		// @TODO
		// 3 dubbele for kan korter hier of volgende keer?
		for (int i = 0; i < size; i++) {
			for (int j = 0; j <= i; j++) {
				if (useMFCC) {
					float average = 0;
					for (int k = 1; k < mfccCoef; k++) { // @TODO: beginnen van
															// 0, 1 of 2 ?
						// euclidean distance
						average += (mfccs[i][k] - mfccs[j][k])
								* (mfccs[i][k] - mfccs[j][k]);
						// average += Math.abs(mfccs[i][k] - mfccs[j][k]);// *
						// (mfccs[i][k] - mfccs[j][k]);
					}
					average = (float) Math.sqrt(average);
					mfcSimilarityMatrix[i][j] = average;
					if (average > maxMFCC) {
						maxMFCC = average;
					}
					if (average < minMFCC) {
						minMFCC = average;
					}
				}
				if (useCQT) {
					float average = 0;
					for (int b = 1; b < cqtcs[i].length; b++) {
						// euclidean distance
						average += (cqtcs[i][b] - cqtcs[j][b])
								* (cqtcs[i][b] - cqtcs[j][b]);
					}
					average = (float) Math.sqrt(average);
					cqtSimilarityMatrix[i][j] = average;
					if (average > maxCQT) {
						maxCQT = average;
					}
					if (average < minCQT) {
						minCQT = average;
					}

				}
				if (useAutoCorrelation) {
					float temp = (float) Math.sqrt(Math
							.abs(autoCorrelationcs[i][0]
									- autoCorrelationcs[j][0]));
					// float temp = Math.abs(autoCorrelationcs[i] -
					// autoCorrelationcs[j]);
					acSimilarityMatrix[i][j] = temp;
					if (temp > maxAC) {
						maxAC = temp;
					}
					if (minAC > temp) {
						minAC = temp;
					}
				}
			}
		}

		// De verhouding van de coeficienten bepalen
		float factor = 0;
		if (useMFCC) {
			factor++;
		}
		if (useAutoCorrelation) {
			factor++;
		}
		if (useCQT) {
			factor++;
		}

		float coeficient = (float) MAX_SCALE_VALUE / factor;

		for (int i = 0; i < size; i++) {
			for (int j = 0; j <= i; j++) {
				similarityMatrix[i][j] = MAX_SCALE_VALUE;
				if (useMFCC) {
					similarityMatrix[i][j] -= (float) (((mfcSimilarityMatrix[i][j] - minMFCC) / (maxMFCC - minMFCC)) * coeficient);
				}
				if (useAutoCorrelation) {
					similarityMatrix[i][j] -= (float) (((acSimilarityMatrix[i][j] - minAC) / (maxAC - minAC)) * coeficient);
				}
				if (useCQT) {
					similarityMatrix[i][j] -= (float) (((cqtSimilarityMatrix[i][j] - minCQT) / (maxCQT - minCQT)) * coeficient);
				}

			}
		}
	}

	public float[][] getNoveltyScore() {
		return noveltyScores;
	}

	public void setNewAudioFile(final File newFile) {
		if (AASModel.getInstance().isGuiEnabled()) {
			// AnnotationPublisher.getInstance().clearTree();
			TranscodingTask transcodingTask = new TranscodingTask(newFile);
			final List<BackgroundTask> detectorTasks = new ArrayList();
			detectorTasks.add(transcodingTask);
			transcodingTask.addHandler(new BackgroundTask.TaskHandler() {

				@Override
				public void taskInterrupted(BackgroundTask backgroundTask,
						Exception e) {
				}

				@Override
				public void taskDone(BackgroundTask backgroundTask) {
					if (backgroundTask instanceof TranscodingTask) {
						setAudioFile(((TranscodingTask) backgroundTask)
								.getAudioFile());
					}
				}
			});
			String title = "Progress: "
					+ FileUtils.basename(newFile.getAbsolutePath());

			// AnnotationPublisher.getInstance().clear();
			// AnnotationPublisher.getInstance().extractionStarted();
			final ProgressDialog dialog = new ProgressDialog(title,
					transcodingTask, detectorTasks);
			dialog.addPropertyChangeListener(new PropertyChangeListener() {

				@Override
				public void propertyChange(PropertyChangeEvent evt) {
					if (evt.getPropertyName().equals("allTasksFinished")) {
						onAudioFileChange();
						// AnnotationPublisher.getInstance().extractionFinished();
					}
				}
			});
			dialog.pack();
			dialog.setVisible(true);
		} else {
			try {
				this.audioFile = new AudioFile(newFile.getAbsolutePath());
				onAudioFileChange();
			} catch (EncoderException e) {
				e.printStackTrace();
			}
		}
	}

	public Segmentation getSegmentation() {
		return segmentation;
	}

	private class SegmentationTask extends BackgroundTask {

		protected SegmentationTask() {
			super("Analysing segmentation", false);
		}

		@Override
		public Void doInBackground() {
			Runnable runSegmentationAnalyser = new Runnable() {

				@Override
				public void run() {
					try {
						AASModel.getInstance().calculate();
					} catch (Exception e) {
						interrupt(SegmentationTask.this, e);
						e.printStackTrace();
					}
				}
			};
			Thread t = new Thread(runSegmentationAnalyser, getName());
			t.start();
			setProgress(50);
			try {
				t.join();
			} catch (Exception e) {
				e.printStackTrace();
			}
			setProgress(100);
			return null;

		}
	}

	private class TranscodingTask extends BackgroundTask {

		private final File newFile;
		AudioFile transcodedAudioFile;

		protected TranscodingTask(final File file) {
			super("Transcoding " + FileUtils.basename(file.getAbsolutePath()),
					false);
			newFile = file;
		}

		@Override
		public Void doInBackground() {
			Runnable runTranscoder = new Runnable() {

				@Override
				public void run() {
					try {
						transcodedAudioFile = new AudioFile(
								newFile.getAbsolutePath());
					} catch (EncoderException e) {
						interrupt(TranscodingTask.this, e);
					}
				}
			};
			// Do the actual detection in the background
			Thread t = new Thread(runTranscoder, getName());
			t.start();
			setProgress(50);
			try {
				t.join();
			} catch (Exception e) {
				e.printStackTrace();
			}
			setProgress(100);
			return null;
		}

		public AudioFile getAudioFile() {
			return transcodedAudioFile;
		}
	}

	private void setAudioFile(final AudioFile newAudioFile) {
		this.audioFile = newAudioFile;
	}

	public boolean isMacroEnabled() {
		return macroEnabled;
	}

	public boolean isMesoEnabled() {
		return mesoEnabled;
	}

	public boolean isMicroEnabled() {
		return microEnabled;
	}

	public void cleanMemory() {
		this.autoCorrelationcs = null;
		this.cqtcs = null;
		this.mfccs = null;
		this.noveltyScores = null;
		this.similarityMatrix = null;

		System.gc();
	}

	public void run() {
		onCalculationStarted();
		if (guiEnabled) {
			SegmentationTask segmentationTask = new SegmentationTask();
			final List<BackgroundTask> detectorTasks = new ArrayList();
			detectorTasks.add(segmentationTask);
			String title = "Progress: "
					+ this.getAudioFile().originalBasename();
			final ProgressDialog dialog = new ProgressDialog(title,
					segmentationTask, detectorTasks);
			dialog.pack();
			dialog.setVisible(true);
		} else {
			this.calculate();
		}
		onCalculationEnd();
	}

	private void onAudioFileChange() {
		// audioFileChanged();
		this.onlyStructureDetection = false;
		this.similarityMatrix = null;
		amountOfFrames = audioFile.fileFormat().getFrameLength()
				/ (frameSize - overlapping);
		samplesPerPixel = (int) Math.pow(
				2,
				(int) Math.floor(Math.log(audioFile.fileFormat()
						.getFrameLength() / 800) / Math.log(2)));
		sampleRate = audioFile.fileFormat().getFormat().getSampleRate();
		segmentation.clearAll();

		File file = new File(audioFile.transcodedPath());
		if (guiEnabled) {
			Player.getInstance().load(file);
		}

		Object[] listeners = audioFileListenerList.getListenerList();
		for (int i = 0; i < listeners.length; i += 2) {
			if (listeners[i] == AudioFileListener.class) {
				((AudioFileListener) listeners[i + 1]).audioFileChanged();
			}
		}
	}

	public synchronized void addAudioFileChangedListener(
			AudioFileListener listener) {
		audioFileListenerList.add(AudioFileListener.class, listener);
	}

	public synchronized void removeAudioFileChangedListener(
			AudioFileListener listener) {
		audioFileListenerList.remove(AudioFileListener.class, listener);
	}

	public boolean isGuiEnabled() {
		return guiEnabled;
	}

	private void addFeaturesToFrame(int frameNr, int featureOrigin,
			float[] features) {
		if (frameNr < 0 || frameNr >= this.amountOfFrames) {
			throw new RuntimeException(
					"Framenumber must be >= 0 and < the amount of frames");
		}
		switch (featureOrigin) {
		case FEATURE_ORIGIN_MFCC:
			this.mfccs[frameNr] = features;
			break;
		case FEATURE_ORIGIN_CQT:
			this.cqtcs[frameNr] = features;
			break;
		case FEATURE_ORIGIN_AUTOCORRELATIE:
			this.autoCorrelationcs[frameNr] = features;
			break;
		default:
			throw new RuntimeException(
					"Please choose a feature origin! (constant in the AASModel class)");
		}
	}

	public float[][] getFeatures(int featureOrigin) {
		switch (featureOrigin) {
		case FEATURE_ORIGIN_MFCC:
			if (mfccs != null && mfccs.length > 0)
				return this.mfccs;
			else
				throw new RuntimeException(
						"No feautures of that origin available! Are you sure they were calculated?");
		case FEATURE_ORIGIN_CQT:
			if (cqtcs != null && cqtcs.length > 0)
				return this.cqtcs;
			else
				throw new RuntimeException(
						"No feautures of that origin available! Are you sure they were calculated?");
		case FEATURE_ORIGIN_AUTOCORRELATIE:
			if (autoCorrelationcs != null && autoCorrelationcs.length > 0)
				return this.autoCorrelationcs;
			else
				throw new RuntimeException(
						"No feautures of that origin available! Are you sure they were calculated?");
		default:
			throw new RuntimeException(
					"Please choose a feature origin! (constants in the AASModel class)");
		}

	}
}
