/*
 * Copyright 2013 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.jenkins.plugins.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.when;

import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.jenkins.plugins.credentials.oauth.GoogleOAuth2ScopeRequirement;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import com.google.jenkins.plugins.util.ConflictException;
import com.google.jenkins.plugins.util.ForbiddenException;
import com.google.jenkins.plugins.util.MockExecutor;
import com.google.jenkins.plugins.util.NotFoundException;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.Verifier;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link ExpiringBucketLifecycleManager}. */
public class ExpiringBucketLifecycleManagerTest {

  @org.junit.Rule public JenkinsRule jenkins = new JenkinsRule();

  @Mock private GoogleRobotCredentials credentials;

  private GoogleCredential credential;

  private ExpiringBucketLifecycleManager underTest;

  private final MockExecutor executor = new MockExecutor();
  private ConflictException conflictException;
  private ForbiddenException forbiddenException;
  private NotFoundException notFoundException;

  private Predicate<Storage.Buckets.Update> checkHasOneRuleLifecycle() {
    return new Predicate<Storage.Buckets.Update>() {
      @Override
      public boolean apply(Storage.Buckets.Update operation) {
        Bucket bucket = (Bucket) operation.getJsonContent();
        assertNotNull(bucket.getLifecycle());
        assertNotNull(bucket.getLifecycle().getRule());
        assertEquals(1, bucket.getLifecycle().getRule().size());
        return true;
      }
    };
  }

  private static class MockUploadModule extends UploadModule {
    public MockUploadModule(MockExecutor executor) {
      this.executor = executor;
    }

    @Override
    public MockExecutor newExecutor() {
      return executor;
    }

    private final MockExecutor executor;
  }

  @org.junit.Rule
  public Verifier verifySawAll =
      new Verifier() {
        @Override
        public void verify() {
          assertTrue(executor.sawAll());
          assertFalse(executor.sawUnexpected());
        }
      };

  private FreeStyleProject project;
  private FreeStyleBuild build;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);

    when(credentials.getId()).thenReturn(CREDENTIALS_ID);
    when(credentials.getProjectId()).thenReturn(PROJECT_ID);

    if (jenkins.jenkins != null) {
      SystemCredentialsProvider.getInstance().getCredentials().add(credentials);

      project = jenkins.createFreeStyleProject("test");
      project
          .getPublishersList()
          .add(
              // Create a storage plugin with no uploaders to fake things out.
              new GoogleCloudStorageUploader(CREDENTIALS_ID, null));
      build = project.scheduleBuild2(0).get();
    }

    credential = new GoogleCredential();
    when(credentials.getGoogleCredential(isA(GoogleOAuth2ScopeRequirement.class)))
        .thenReturn(credential);

    // Return ourselves as remotable
    when(credentials.forRemote(isA(GoogleOAuth2ScopeRequirement.class))).thenReturn(credentials);

    notFoundException = new NotFoundException();
    conflictException = new ConflictException();
    forbiddenException = new ForbiddenException();

    underTest =
        new ExpiringBucketLifecycleManager(
            BUCKET_URI,
            new MockUploadModule(executor),
            TTL,
            null /* legacy arg */,
            null /* legacy arg */);
  }

  @Test
  @WithoutJenkins
  public void testGetters() {
    assertEquals(BUCKET_URI, underTest.getBucket());
    assertEquals(TTL, underTest.getTtl());
  }

  @Test
  @WithoutJenkins
  public void testGettersWithLegacy() {
    underTest =
        new ExpiringBucketLifecycleManager(
            null /* bucket */, new MockUploadModule(executor), null /* ttl */, BUCKET_URI, TTL);
    assertEquals(BUCKET_URI, underTest.getBucket());
    assertEquals(TTL, underTest.getTtl());
  }

  @Test
  public void testFailingCheckWithAnnotation() throws Exception {
    final Bucket bucket = new Bucket().setName(BUCKET_NAME);

    // A get that returns a bucket should trigger a check/decorate/update
    executor.when(Storage.Buckets.Get.class, bucket);
    executor.passThruWhen(Storage.Buckets.Update.class, checkHasOneRuleLifecycle());

    underTest.perform(CREDENTIALS_ID, build, TaskListener.NULL);
  }

  @Test
  public void testBadTTLWithUpdate() throws Exception {
    final Bucket bucket =
        new Bucket()
            .setName(BUCKET_NAME)
            .setLifecycle(
                new Bucket.Lifecycle()
                    .setRule(
                        ImmutableList.of(
                            new Rule()
                                .setCondition(new Rule.Condition().setAge(BAD_TTL))
                                .setAction(new Rule.Action().setType("Delete")))));

    // A get that returns a bucket should trigger a check/decorate/update
    executor.when(Storage.Buckets.Get.class, bucket);
    executor.passThruWhen(Storage.Buckets.Update.class, checkHasOneRuleLifecycle());

    underTest.perform(CREDENTIALS_ID, build, TaskListener.NULL);
  }

  @Test
  public void testReplaceComplexLifecycle() throws Exception {
    final Rule expireGoodTTL =
        new Rule()
            .setCondition(new Rule.Condition().setAge(TTL))
            .setAction(new Rule.Action().setType("Delete"));
    final Bucket bucket =
        new Bucket()
            .setName(BUCKET_NAME)
            .setLifecycle(
                new Bucket.Lifecycle()
                    .setRule(
                        // Create a list with two good rules, to validate that
                        // multi-clause rules get thrown out.
                        ImmutableList.of(expireGoodTTL, expireGoodTTL)));

    // A get that returns a bucket should trigger a check/decorate/update
    executor.when(Storage.Buckets.Get.class, bucket);
    executor.passThruWhen(Storage.Buckets.Update.class, checkHasOneRuleLifecycle());

    underTest.perform(CREDENTIALS_ID, build, TaskListener.NULL);
  }

  @Test
  public void testBadAction() throws Exception {
    final Bucket bucket =
        new Bucket()
            .setName(BUCKET_NAME)
            .setLifecycle(
                new Bucket.Lifecycle()
                    .setRule(
                        ImmutableList.of(
                            new Rule()
                                .setCondition(new Rule.Condition().setAge(TTL))
                                .setAction(new Rule.Action().setType("Unknown")))));

    // A get that returns a bucket should trigger a check/decorate/update
    executor.when(Storage.Buckets.Get.class, bucket);
    executor.passThruWhen(Storage.Buckets.Update.class, checkHasOneRuleLifecycle());

    underTest.perform(CREDENTIALS_ID, build, TaskListener.NULL);
  }

  @Test
  public void testBadCondition() throws Exception {
    final Bucket bucket =
        new Bucket()
            .setName(BUCKET_NAME)
            .setLifecycle(
                new Bucket.Lifecycle()
                    .setRule(
                        ImmutableList.of(
                            new Rule()
                                .setCondition(new Rule.Condition().setNumNewerVersions(3))
                                .setAction(new Rule.Action().setType("Delete")))));

    // A get that returns a bucket should trigger a check/decorate/update
    executor.when(Storage.Buckets.Get.class, bucket);
    executor.passThruWhen(Storage.Buckets.Update.class, checkHasOneRuleLifecycle());

    underTest.perform(CREDENTIALS_ID, build, TaskListener.NULL);
  }

  @Test
  public void testBadComplexCondition() throws Exception {
    final Bucket bucket =
        new Bucket()
            .setName(BUCKET_NAME)
            .setLifecycle(
                new Bucket.Lifecycle()
                    .setRule(
                        ImmutableList.of(
                            new Rule()
                                .setCondition(
                                    new Rule.Condition().setAge(TTL).setNumNewerVersions(3))
                                .setAction(new Rule.Action().setType("Delete")))));

    // A get that returns a bucket should trigger a check/decorate/update
    executor.when(Storage.Buckets.Get.class, bucket);
    executor.passThruWhen(Storage.Buckets.Update.class, checkHasOneRuleLifecycle());

    underTest.perform(CREDENTIALS_ID, build, TaskListener.NULL);
  }

  @Test
  public void testPassingCheck() throws Exception {
    final Bucket bucket =
        new Bucket()
            .setName(BUCKET_NAME)
            .setLifecycle(
                new Bucket.Lifecycle()
                    .setRule(
                        ImmutableList.of(
                            new Rule()
                                .setCondition(new Rule.Condition().setAge(TTL))
                                .setAction(new Rule.Action().setType("dElEtE")))));

    // A get that returns a bucket should trigger a check/decorate/update
    executor.when(Storage.Buckets.Get.class, bucket);

    underTest.perform(CREDENTIALS_ID, build, TaskListener.NULL);
  }

  private static final String PROJECT_ID = "foo.com:bar-baz";
  private static final String CREDENTIALS_ID = "bazinga";

  private static final String BUCKET_NAME = "ma-bucket";
  private static final String BUCKET_URI = "gs://" + BUCKET_NAME;
  private static final int TTL = 42;
  private static final int BAD_TTL = 420;
}
