// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.storagevolume;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.staros.proto.ADLS2CredentialInfo;
import com.staros.proto.ADLS2FileStoreInfo;
import com.staros.proto.AwsCredentialInfo;
import com.staros.proto.AzBlobCredentialInfo;
import com.staros.proto.AzBlobFileStoreInfo;
import com.staros.proto.FileStoreInfo;
import com.staros.proto.GSFileStoreInfo;
import com.staros.proto.HDFSFileStoreInfo;
import com.staros.proto.S3FileStoreInfo;
import com.starrocks.common.DdlException;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.common.proc.BaseProcResult;
import com.starrocks.connector.share.credential.CloudConfigurationConstants;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.credential.CloudConfigurationFactory;
import com.starrocks.credential.CloudType;
import com.starrocks.persist.gson.GsonPostProcessable;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.SemanticException;

import java.io.DataInput;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StorageVolume implements Writable, GsonPostProcessable {
    public enum StorageVolumeType {
        UNKNOWN,
        S3,
        HDFS,
        AZBLOB,
        ADLS2,
        GS
    }

    // Without id, the scenario like "create storage volume 'a', drop storage volume 'a', create storage volume 'a'"
    // can not be handled. They will be treated as the same storage volume.
    @SerializedName("i")
    private String id;

    @SerializedName("n")
    private String name;

    @SerializedName("s")
    private StorageVolumeType svt;

    @SerializedName("l")
    private List<String> locations;

    private CloudConfiguration cloudConfiguration;

    @SerializedName("p")
    private Map<String, String> params;

    @SerializedName("c")
    private String comment;

    @SerializedName("e")
    private boolean enabled;

    public static String CREDENTIAL_MASK = "******";

    private String dumpMaskedParams(Map<String, String> params) {
        Gson gson = new Gson();
        Map<String, String> maskedParams = new HashMap<>(params);
        addMaskForCredential(maskedParams);
        return gson.toJson(maskedParams);
    }

    public StorageVolume(String id, String name, String svt, List<String> locations,
                         Map<String, String> params, boolean enabled, String comment) throws DdlException {
        this.id = id;
        this.name = name;
        this.svt = toStorageVolumeType(svt);
        this.locations = new ArrayList<>(locations);
        this.comment = comment;
        this.enabled = enabled;
        this.params = new HashMap<>(params);
        Map<String, String> configurationParams = new HashMap<>(params);
        preprocessAuthenticationIfNeeded(configurationParams);
        this.cloudConfiguration = CloudConfigurationFactory.buildCloudConfigurationForStorage(configurationParams, true);
        if (!isValidCloudConfiguration()) {
            throw new SemanticException("Storage params is not valid " + dumpMaskedParams(params));
        }
        validateStorageVolumeConstraints();
    }

    public StorageVolume(StorageVolume sv) throws DdlException {
        this.id = sv.id;
        this.name = sv.name;
        this.svt = sv.svt;
        this.locations = new ArrayList<>(sv.locations);
        this.comment = sv.comment;
        this.enabled = sv.enabled;
        this.cloudConfiguration = CloudConfigurationFactory.buildCloudConfigurationForStorage(sv.params, true);
        this.params = new HashMap<>(sv.params);
        validateStorageVolumeConstraints();
    }

    private void validateStorageVolumeConstraints() throws DdlException {
        if (svt == StorageVolumeType.S3) {
            boolean enablePartitionedPrefix = Boolean.parseBoolean(
                    params.getOrDefault(CloudConfigurationConstants.AWS_S3_ENABLE_PARTITIONED_PREFIX, "false"));
            if (enablePartitionedPrefix) {
                for (String location : locations) {
                    URI uri = URI.create(location);
                    if (!uri.getPath().isEmpty() && !"/".equals(uri.getPath())) {
                        throw new DdlException(String.format(
                                "Storage volume '%s' has '%s'='true', the location '%s'" +
                                        " should not contain sub path after bucket name!",
                                this.name, CloudConfigurationConstants.AWS_S3_ENABLE_PARTITIONED_PREFIX, location));
                    }
                }
            }
        }
    }

    public void setCloudConfiguration(Map<String, String> params) {
        Map<String, String> newParams = new HashMap<>(this.params);
        newParams.putAll(params);
        this.cloudConfiguration = CloudConfigurationFactory.buildCloudConfigurationForStorage(newParams, true);
        if (!isValidCloudConfiguration()) {
            throw new SemanticException("Storage params is not valid " + dumpMaskedParams(newParams));
        }
        this.params = newParams;
    }

    public CloudConfiguration getCloudConfiguration() {
        return cloudConfiguration;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getComment() {
        return comment;
    }

    public void setLocations(List<String> locations) {
        this.locations = locations;
    }

    public List<String> getLocations() {
        return locations;
    }

    public void setType(String type) {
        this.svt = toStorageVolumeType(type);
    }

    public String getType() {
        return svt.toString();
    }

    public Map<String, String> getProperties() {
        return params;
    }

    public static StorageVolumeType toStorageVolumeType(String svt) {
        switch (svt.toLowerCase()) {
            case "s3":
                return StorageVolumeType.S3;
            case "hdfs":
                return StorageVolumeType.HDFS;
            case "azblob":
                return StorageVolumeType.AZBLOB;
            case "adls2":
                return StorageVolumeType.ADLS2;
            case "gs":
                return StorageVolumeType.GS;
            default:
                return StorageVolumeType.UNKNOWN;
        }
    }

    private boolean isValidCloudConfiguration() {
        switch (svt) {
            case S3:
                return cloudConfiguration.getCloudType() == CloudType.AWS;
            case HDFS:
                return cloudConfiguration.getCloudType() == CloudType.HDFS;
            case AZBLOB:
                return cloudConfiguration.getCloudType() == CloudType.AZURE;
            case ADLS2:
                return cloudConfiguration.getCloudType() == CloudType.AZURE;
            case GS:
                return cloudConfiguration.getCloudType() == CloudType.GCP;
            default:
                return false;
        }
    }

    public static void addMaskForCredential(Map<String, String> params) {
        params.computeIfPresent(CloudConfigurationConstants.AWS_S3_ACCESS_KEY, (key, value) -> CREDENTIAL_MASK);
        params.computeIfPresent(CloudConfigurationConstants.AWS_S3_SECRET_KEY, (key, value) -> CREDENTIAL_MASK);
        params.computeIfPresent(CloudConfigurationConstants.AZURE_BLOB_SHARED_KEY, (key, value) -> CREDENTIAL_MASK);
        params.computeIfPresent(CloudConfigurationConstants.AZURE_BLOB_SAS_TOKEN, (key, value) -> CREDENTIAL_MASK);
        params.computeIfPresent(CloudConfigurationConstants.AZURE_ADLS2_SHARED_KEY, (key, value) -> CREDENTIAL_MASK);
        params.computeIfPresent(CloudConfigurationConstants.AZURE_ADLS2_SAS_TOKEN, (key, value) -> CREDENTIAL_MASK);
        params.computeIfPresent(CloudConfigurationConstants.GCP_GCS_SERVICE_ACCOUNT_EMAIL, (key, value) -> CREDENTIAL_MASK);
        params.computeIfPresent(CloudConfigurationConstants.GCP_GCS_SERVICE_ACCOUNT_PRIVATE_KEY_ID,
                (key, value) -> CREDENTIAL_MASK);
        params.computeIfPresent(CloudConfigurationConstants.GCP_GCS_SERVICE_ACCOUNT_PRIVATE_KEY, (key, value) -> CREDENTIAL_MASK);
    }

    public void getProcNodeData(BaseProcResult result) {
        result.addRow(Lists.newArrayList(name,
                svt.name(),
                String.valueOf(GlobalStateMgr.getCurrentState().getStorageVolumeMgr()
                        .getDefaultStorageVolumeId().equals(id)),
                Joiner.on(", ").join(locations),
                dumpMaskedParams(params),
                String.valueOf(enabled),
                String.valueOf(comment)));
    }

    public static FileStoreInfo createFileStoreInfo(String name, String svt,
                                                    List<String> locations, Map<String, String> params,
                                                    boolean enabled, String comment) throws DdlException {
        StorageVolume sv = new StorageVolume("", name, svt, locations, params, enabled, comment);
        return sv.toFileStoreInfo();
    }

    public FileStoreInfo toFileStoreInfo() {
        FileStoreInfo.Builder builder = cloudConfiguration.toFileStoreInfo().toBuilder();
        builder.setFsKey(id)
                .setFsName(this.name)
                .setComment(this.comment)
                .setEnabled(this.enabled)
                .addAllLocations(locations);
        return builder.build();
    }

    public static StorageVolume fromFileStoreInfo(FileStoreInfo fsInfo) throws DdlException {
        String svt = fsInfo.getFsType().toString();
        Map<String, String> params = getParamsFromFileStoreInfo(fsInfo);
        return new StorageVolume(fsInfo.getFsKey(), fsInfo.getFsName(), svt,
                fsInfo.getLocationsList(), params, fsInfo.getEnabled(), fsInfo.getComment());
    }

    public static Map<String, String> getParamsFromFileStoreInfo(FileStoreInfo fsInfo) {
        Map<String, String> params = new HashMap<>();
        switch (fsInfo.getFsType()) {
            case S3:
                S3FileStoreInfo s3FileStoreInfo = fsInfo.getS3FsInfo();
                params.put(CloudConfigurationConstants.AWS_S3_REGION, s3FileStoreInfo.getRegion());
                params.put(CloudConfigurationConstants.AWS_S3_ENDPOINT, s3FileStoreInfo.getEndpoint());
                if (s3FileStoreInfo.getPartitionedPrefixEnabled()) {
                    // Don't show the parameters if not enabled.
                    params.put(CloudConfigurationConstants.AWS_S3_ENABLE_PARTITIONED_PREFIX,
                            Boolean.toString(true));
                    params.put(CloudConfigurationConstants.AWS_S3_NUM_PARTITIONED_PREFIX,
                            Integer.toString(s3FileStoreInfo.getNumPartitionedPrefix()));
                }
                AwsCredentialInfo credentialInfo = s3FileStoreInfo.getCredential();
                if (credentialInfo.hasSimpleCredential()) {
                    params.put(CloudConfigurationConstants.AWS_S3_USE_INSTANCE_PROFILE, "false");
                    params.put(CloudConfigurationConstants.AWS_S3_USE_AWS_SDK_DEFAULT_BEHAVIOR, "false");
                    params.put(CloudConfigurationConstants.AWS_S3_USE_WEB_IDENTITY_TOKEN_FILE, "false");
                    params.put(CloudConfigurationConstants.AWS_S3_ACCESS_KEY,
                            credentialInfo.getSimpleCredential().getAccessKey());
                    params.put(CloudConfigurationConstants.AWS_S3_SECRET_KEY,
                            credentialInfo.getSimpleCredential().getAccessKeySecret());
                } else if (credentialInfo.hasAssumeRoleCredential()) {
                    params.put(CloudConfigurationConstants.AWS_S3_USE_INSTANCE_PROFILE, "true");
                    params.put(CloudConfigurationConstants.AWS_S3_USE_AWS_SDK_DEFAULT_BEHAVIOR, "false");
                    params.put(CloudConfigurationConstants.AWS_S3_USE_WEB_IDENTITY_TOKEN_FILE, "false");
                    params.put(CloudConfigurationConstants.AWS_S3_IAM_ROLE_ARN,
                            credentialInfo.getAssumeRoleCredential().getIamRoleArn());
                    params.put(CloudConfigurationConstants.AWS_S3_EXTERNAL_ID,
                            credentialInfo.getAssumeRoleCredential().getExternalId());
                } else if (credentialInfo.hasProfileCredential()) {
                    params.put(CloudConfigurationConstants.AWS_S3_USE_INSTANCE_PROFILE, "true");
                    params.put(CloudConfigurationConstants.AWS_S3_USE_AWS_SDK_DEFAULT_BEHAVIOR, "false");
                    params.put(CloudConfigurationConstants.AWS_S3_USE_WEB_IDENTITY_TOKEN_FILE, "false");
                } else if (credentialInfo.hasDefaultCredential()) {
                    params.put(CloudConfigurationConstants.AWS_S3_USE_AWS_SDK_DEFAULT_BEHAVIOR, "true");
                }
                return params;
            case HDFS:
                HDFSFileStoreInfo hdfsFileStoreInfo = fsInfo.getHdfsFsInfo();
                params.putAll(hdfsFileStoreInfo.getConfigurationMap());
                String userName = hdfsFileStoreInfo.getUsername();
                if (!Strings.isNullOrEmpty(userName)) {
                    params.put(CloudConfigurationConstants.HDFS_USERNAME_DEPRECATED, userName);
                }
                return params;
            case AZBLOB: {
                AzBlobFileStoreInfo azBlobFileStoreInfo = fsInfo.getAzblobFsInfo();
                params.put(CloudConfigurationConstants.AZURE_BLOB_ENDPOINT, azBlobFileStoreInfo.getEndpoint());
                AzBlobCredentialInfo azBlobcredentialInfo = azBlobFileStoreInfo.getCredential();
                String sharedKey = azBlobcredentialInfo.getSharedKey();
                if (!Strings.isNullOrEmpty(sharedKey)) {
                    params.put(CloudConfigurationConstants.AZURE_BLOB_SHARED_KEY, sharedKey);
                }
                String sasToken = azBlobcredentialInfo.getSasToken();
                if (!Strings.isNullOrEmpty(sasToken)) {
                    params.put(CloudConfigurationConstants.AZURE_BLOB_SAS_TOKEN, sasToken);
                }
                return params;
            }
            case ADLS2: {
                ADLS2FileStoreInfo adls2FileStoreInfo = fsInfo.getAdls2FsInfo();
                params.put(CloudConfigurationConstants.AZURE_ADLS2_ENDPOINT, adls2FileStoreInfo.getEndpoint());
                ADLS2CredentialInfo adls2credentialInfo = adls2FileStoreInfo.getCredential();
                String sharedKey = adls2credentialInfo.getSharedKey();
                if (!Strings.isNullOrEmpty(sharedKey)) {
                    params.put(CloudConfigurationConstants.AZURE_ADLS2_SHARED_KEY, sharedKey);
                }
                String sasToken = adls2credentialInfo.getSasToken();
                if (!Strings.isNullOrEmpty(sasToken)) {
                    params.put(CloudConfigurationConstants.AZURE_ADLS2_SAS_TOKEN, sasToken);
                }
                String tenantId = adls2credentialInfo.getTenantId();
                if (!Strings.isNullOrEmpty(tenantId)) {
                    params.put(CloudConfigurationConstants.AZURE_ADLS2_OAUTH2_TENANT_ID, tenantId);
                }
                String clientId = adls2credentialInfo.getClientId();
                if (!Strings.isNullOrEmpty(clientId)) {
                    params.put(CloudConfigurationConstants.AZURE_ADLS2_OAUTH2_CLIENT_ID, clientId);
                }
                if (!Strings.isNullOrEmpty(tenantId) && !Strings.isNullOrEmpty(clientId)) {
                    params.put(CloudConfigurationConstants.AZURE_ADLS2_OAUTH2_USE_MANAGED_IDENTITY, "true");
                }
                String clientSecret = adls2credentialInfo.getClientSecret();
                if (!Strings.isNullOrEmpty(clientSecret)) {
                    params.put(CloudConfigurationConstants.AZURE_ADLS2_OAUTH2_CLIENT_SECRET, clientSecret);
                }
                String clientEndpoint = adls2credentialInfo.getAuthorityHost();
                if (!Strings.isNullOrEmpty(clientEndpoint)) {
                    params.put(CloudConfigurationConstants.AZURE_ADLS2_OAUTH2_CLIENT_ENDPOINT, clientEndpoint);
                }
                return params;
            }
            case GS: {
                GSFileStoreInfo gsFileStoreInfo = fsInfo.getGsFsInfo();
                params.put(CloudConfigurationConstants.GCP_GCS_ENDPOINT, gsFileStoreInfo.getEndpoint());
                if (gsFileStoreInfo.getUseComputeEngineServiceAccount()) {
                    params.put(CloudConfigurationConstants.GCP_GCS_USE_COMPUTE_ENGINE_SERVICE_ACCOUNT, "true");
                } else {
                    params.put(CloudConfigurationConstants.GCP_GCS_USE_COMPUTE_ENGINE_SERVICE_ACCOUNT, "false");
                    params.put(CloudConfigurationConstants.GCP_GCS_SERVICE_ACCOUNT_EMAIL,
                            gsFileStoreInfo.getServiceAccountEmail());
                    params.put(CloudConfigurationConstants.GCP_GCS_SERVICE_ACCOUNT_PRIVATE_KEY_ID,
                            gsFileStoreInfo.getServiceAccountPrivateKeyId());
                    params.put(CloudConfigurationConstants.GCP_GCS_SERVICE_ACCOUNT_PRIVATE_KEY,
                            gsFileStoreInfo.getServiceAccountPrivateKey());
                }
                if (!Strings.isNullOrEmpty(gsFileStoreInfo.getImpersonation())) {
                    params.put(CloudConfigurationConstants.GCP_GCS_USE_COMPUTE_ENGINE_SERVICE_ACCOUNT,
                            gsFileStoreInfo.getImpersonation());
                }
                return params;
            }
            default:
                return params;
        }
    }

    private void preprocessAuthenticationIfNeeded(Map<String, String> params) {
        if (svt == StorageVolumeType.AZBLOB) {
            String container = locations.get(0).split("/")[0];
            params.put(CloudConfigurationConstants.AZURE_BLOB_CONTAINER, container);
        }
    }



    public static StorageVolume read(DataInput in) throws IOException {
        String json = Text.readString(in);
        return GsonUtils.GSON.fromJson(json, StorageVolume.class);
    }

    @Override
    public void gsonPostProcess() throws IOException {
        cloudConfiguration = CloudConfigurationFactory.buildCloudConfigurationForStorage(params);
    }
}
