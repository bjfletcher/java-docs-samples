/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.examples.cloud.speech;

import static org.apache.log4j.ConsoleAppender.SYSTEM_OUT;

import com.google.cloud.speech.v1beta1.RecognitionConfig;
import com.google.cloud.speech.v1beta1.RecognitionConfig.AudioEncoding;
import com.google.cloud.speech.v1beta1.SpeechContext;
import com.google.cloud.speech.v1beta1.SpeechGrpc;
import com.google.cloud.speech.v1beta1.StreamingRecognitionConfig;
import com.google.cloud.speech.v1beta1.StreamingRecognizeRequest;
import com.google.cloud.speech.v1beta1.StreamingRecognizeResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;

import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.*;


/**
 * Client that sends streaming audio to Speech.Recognize and returns streaming transcript.
 */
public class StreamingRecognizeClient {

  private static final Logger logger = Logger.getLogger(StreamingRecognizeClient.class.getName());

  private final ManagedChannel channel;

  private final SpeechGrpc.SpeechStub speechClient;

	private final TargetDataLine line;

  private static final int SAMPLE_RATE = 48000;

  private static final int BYTES_PER_BUFFER = 12800; //buffer size in bytes
  private static final int BYTES_PER_SAMPLE = 2; //bytes per sample for LINEAR16

  private static final List<String> OAUTH2_SCOPES =
      Arrays.asList("https://www.googleapis.com/auth/cloud-platform");

  /**
   * Construct client connecting to Cloud Speech server at {@code host:port}.
   */
  public StreamingRecognizeClient(ManagedChannel channel)
      throws IOException {
    this.channel = channel;

    speechClient = SpeechGrpc.newStub(channel);

    //Send log4j logs to Console
    //If you are going to run this on GCE, you might wish to integrate with gcloud-java logging.
    //See https://github.com/GoogleCloudPlatform/gcloud-java/blob/master/README.md#stackdriver-logging-alpha
    
    ConsoleAppender appender = new ConsoleAppender(new SimpleLayout(), SYSTEM_OUT);
    logger.addAppender(appender);

		this.line = this.getSoundLine();
  }

	private TargetDataLine getSoundLine() throws IOException {
		TargetDataLine line;
		AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, 1, 2, SAMPLE_RATE, false);
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, 
				format); // format is an AudioFormat object
		if (!AudioSystem.isLineSupported(info)) {
			// Handle the error ... 
			throw new IOException("Audio not supported");
		}
		// Obtain and open the line.
		try {
			line = (TargetDataLine) AudioSystem.getLine(info);
			line.open(format);
		} catch (LineUnavailableException ex) {
			// Handle the error ... 
			throw new IOException(ex);
		}
		return line;
	}

  public void shutdown() throws InterruptedException {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Send streaming recognize requests to server. */
  public void recognize() throws InterruptedException, IOException {
    final CountDownLatch finishLatch = new CountDownLatch(1);
    StreamObserver<StreamingRecognizeResponse> responseObserver =
        new StreamObserver<StreamingRecognizeResponse>() {
          @Override
          public void onNext(StreamingRecognizeResponse response) {
            logger.info("Received response: " + TextFormat.printToString(response));
          }

          @Override
          public void onError(Throwable error) {
            logger.log(Level.WARN, "recognize failed: {0}", error);
            finishLatch.countDown();
          }

          @Override
          public void onCompleted() {
            logger.info("recognize completed.");
            finishLatch.countDown();
          }
        };

    StreamObserver<StreamingRecognizeRequest> requestObserver =
        speechClient.streamingRecognize(responseObserver);
    try {
      // Build and send a StreamingRecognizeRequest containing the parameters for
      // processing the audio.
			SpeechContext context = SpeechContext.newBuilder()
        .addPhrases("Dale")
				.addPhrases("Dale Lane")
				.addPhrases("IBM")
				.addPhrases("Watson")
        .build();
      RecognitionConfig config =
          RecognitionConfig.newBuilder()
              .setEncoding(AudioEncoding.LINEAR16)
              .setSampleRate(SAMPLE_RATE)
							.setLanguageCode("en-GB")
							.setSpeechContext(context)
              .build();
      StreamingRecognitionConfig streamingConfig =
          StreamingRecognitionConfig.newBuilder()
              .setConfig(config)
              .setSingleUtterance(false)
              .setInterimResults(true)
              .build();

      StreamingRecognizeRequest initial =
          StreamingRecognizeRequest.newBuilder().setStreamingConfig(streamingConfig).build();
      requestObserver.onNext(initial);

			// Assume that the TargetDataLine, line, has already
			// been obtained and opened.
			int numBytesRead;
			int totalBytes = 0;
			byte[] data = new byte[BYTES_PER_BUFFER];

			// Begin audio capture.
			this.line.start();

			// Here, stopped is a global boolean set by another thread.
			// FIXME: the above is ignored
			boolean stopped = false;
			while (!stopped) {
				// Read the next chunk of data from the TargetDataLine.
				numBytesRead = this.line.read(data, 0, data.length);
				totalBytes += numBytesRead;
        StreamingRecognizeRequest request =
            StreamingRecognizeRequest.newBuilder()
                .setAudioContent(ByteString.copyFrom(data, 0, numBytesRead))
                .build();
        requestObserver.onNext(request);
			}     

      logger.info("Sent " + totalBytes + " bytes from the mic");
    } catch (RuntimeException e) {
      // Cancel RPC.
      requestObserver.onError(e);
      throw e;
    }
    // Mark the end of requests.
    requestObserver.onCompleted();

    // Receiving happens asynchronously.
    finishLatch.await(1, TimeUnit.MINUTES);
  }

  public static void main(String[] args) throws Exception {

    String host = "speech.googleapis.com";
    Integer port = 443;

    ManagedChannel channel = AsyncRecognizeClient.createChannel(host, port);
    StreamingRecognizeClient client = new StreamingRecognizeClient(channel);
    try {
      client.recognize();
    } finally {
      client.shutdown();
    }
  }
}
