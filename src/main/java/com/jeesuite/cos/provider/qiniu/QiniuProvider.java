package com.jeesuite.cos.provider.qiniu;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;

import com.jeesuite.common.JeesuiteBaseException;
import com.jeesuite.common.json.JsonUtils;
import com.jeesuite.cos.CObjectMetadata;
import com.jeesuite.cos.CUploadObject;
import com.jeesuite.cos.CUploadResult;
import com.jeesuite.cos.CosProviderConfig;
import com.jeesuite.cos.FilePathHelper;
import com.jeesuite.cos.UploadTokenParam;
import com.jeesuite.cos.provider.AbstractProvider;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.util.Auth;
import com.qiniu.util.StringMap;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * 七牛文件服务
 * 
 * @description <br>
 * @author <a href="mailto:vakinge@gmail.com">vakin</a>
 * @date 2017年1月5日
 */
public class QiniuProvider extends AbstractProvider {

	private static OkHttpClient httpClient = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
			.build();
	
	private static final String DEFAULT_REGION_NAME = "z2";
	public static final String NAME = "qiniu";
	private static final String DEFAULT_CALLBACK_BODY = "filename=${fname}&size=${fsize}&mimeType=${mimeType}&height=${imageInfo.height}&width=${imageInfo.width}";
	
	private Map<String, ExpireableUpToken> bucketUploadTokenCache = new HashMap<>();
	
	private static final String[] policyFields = new String[]{
            "callbackUrl",
            "callbackBody",
            "callbackHost",
            "callbackBodyType",
            "fileType",
            "saveKey",
            "mimeLimit",
            "fsizeLimit",
            "fsizeMin",
            "deleteAfterDays",
    };

	private static UploadManager uploadManager;
	private static BucketManager bucketManager;
	private Auth auth;

	public QiniuProvider(CosProviderConfig conf) {
		super(conf);
		auth = Auth.create(conf.getAccessKey(), conf.getSecretKey());

		Region region;
		if(DEFAULT_REGION_NAME.equals(conf.getRegionName())){
			region = Region.huanan();
		}else{
			region = Region.huanan();
			conf.setRegionName(DEFAULT_REGION_NAME);
		}
		Configuration c = new Configuration(region);
		uploadManager = new UploadManager(c);
		bucketManager = new BucketManager(auth,c);
	}
	
	@Override
	public void createBucket(String bucketName) {
		try {
			bucketName = currentBucketName(bucketName);
			bucketManager.createBucket(bucketName, conf.getRegionName());
		} catch (QiniuException e) {
			processQiniuException(bucketName, e);
		}
	}

	@Override
	public void deleteBucket(String bucketName) {
		bucketName = currentBucketName(bucketName);
		String path = "/drop/"+bucketName+"\n";
        String accessToken = auth.sign(path);
        String url = "http://rs.qiniu.com/drop/"+bucketName;                

        Request request = new Request.Builder().url(url)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("Authorization", "QBox " + accessToken).build();
        okhttp3.Response re = null;
        try {
            re = httpClient.newCall(request).execute();
            if (!re.isSuccessful()) {
            	throw new JeesuiteBaseException(re.code(), re.message());
            }
        } catch (IOException e) {
        	throw new JeesuiteBaseException(e.getMessage());
        }
	}
	
	@Override
	public boolean exists(String bucketName,String fileKey) {
		bucketName = currentBucketName(bucketName);
		fileKey = resolveFileKey(bucketName, fileKey);
		try {
			bucketManager.stat(bucketName, fileKey);
			return true;
		} catch (QiniuException e) {
			if(e.code() == 612)return false;
			throw new JeesuiteBaseException(e.code(), e.getMessage());
		}
	}

	@Override
	public CUploadResult upload(CUploadObject object) {
		String fileKey = object.buildFileKey();
		String bucketName = currentBucketName(object.getBucketName());
		try {
			Response res = null;
			String upToken = getUpToken(bucketName,object.getMetadata());
			if(object.getFile() != null){
				res = uploadManager.put(object.getFile(), fileKey, upToken);
			}else if(object.getBytes() != null){
				res = uploadManager.put(object.getBytes(), fileKey, upToken);
			}else if(object.getInputStream() != null){
				res = uploadManager.put(object.getInputStream(), fileKey, upToken, null, object.getMimeType());
			}else{
				throw new IllegalArgumentException("upload object is NULL");
			}
			
			if (res.isOK()) {
				return new CUploadResult(null, fileKey,getFullPath(object.getBucketName(),fileKey), null);
			}
			
		} catch (QiniuException e) {
			processQiniuException(object.getFileKey(), e);
		}
		return null;
	}

	@Override
	protected String generatePresignedUrl(String bucketName,String fileKey,int expireInSeconds) {
		bucketName = currentBucketName(bucketName);
		String path = getFullPath(bucketName,fileKey);
		return auth.privateDownloadUrl(path, expireInSeconds);
	}

	@Override
	public boolean delete(String bucketName,String fileKey) {
		try {
			bucketName = currentBucketName(bucketName);
			bucketManager.delete(bucketName, fileKey);
			return true;
		} catch (QiniuException e) {
			//不存在
			if(e.code() == 612)return true;
			processQiniuException(fileKey, e);
		}
		return false;
	}
	
	@Override
	public byte[] getObjectBytes(String bucketName, String fileKey) {
		bucketName = currentBucketName(bucketName);
		String downloadUrl = getDownloadUrl(bucketName, fileKey,600);
		Request request = new Request.Builder().url(downloadUrl).build();
		okhttp3.Response re = null;
		try {
			re = httpClient.newCall(request).execute();
			if(re.isSuccessful()){
				return re.body().bytes();
			}
			throw new JeesuiteBaseException(re.code(),re.message());
		} catch (IOException e) {
			throw new JeesuiteBaseException(e.getMessage());
		}
	}

	@Override
	public InputStream getObjectInputStream(String bucketName, String fileKey) {
		bucketName = currentBucketName(bucketName);
		String downloadUrl = getDownloadUrl(bucketName, fileKey,600);
		Request request = new Request.Builder().url(downloadUrl).build();
		okhttp3.Response re = null;
		try {
			re = httpClient.newCall(request).execute();
			if(re.isSuccessful()){
				return re.body().byteStream();
			}
			throw new JeesuiteBaseException(re.code(),re.message());
		} catch (IOException e) {
			throw new JeesuiteBaseException(e.getMessage());
		}
	}

	@Override
	public CObjectMetadata getObjectMetadata(String bucketName, String fileKey) {
		try {
			bucketName = currentBucketName(bucketName);
			FileInfo stat = bucketManager.stat(bucketName, fileKey);
			CObjectMetadata objectMetadata = new CObjectMetadata();
			objectMetadata.setCreateTime(new Date(stat.putTime));
			objectMetadata.setFilesize(stat.fsize);
			objectMetadata.setHash(stat.md5);
			objectMetadata.setMimeType(stat.mimeType);
			return objectMetadata;
		} catch (QiniuException e) {
			processQiniuException(bucketName, e);
			return null;
		}
	}
	
	@Override
	public Map<String, Object> createUploadToken(UploadTokenParam param) {
		
		if(StringUtils.isNotBlank(param.getCallbackUrl())){
			if(StringUtils.isBlank(param.getCallbackBody())){
				param.setCallbackBody(DEFAULT_CALLBACK_BODY);
			}
			if(StringUtils.isBlank(param.getCallbackHost())){
				param.setCallbackHost(param.getCallbackHost());
			}
		}
		
		Map<String, Object> result = new HashMap<>();
		StringMap policy = new StringMap();
		policy.putNotNull(policyFields[0], param.getCallbackUrl());
		policy.putNotNull(policyFields[1], param.getCallbackBody());
		policy.putNotNull(policyFields[2], param.getCallbackHost());
		policy.putNotNull(policyFields[3], param.getCallbackBodyType());
		policy.putNotNull(policyFields[4], param.getFileType());
		policy.putNotNull(policyFields[5], param.getFileKey());
		policy.putNotNull(policyFields[6], param.getMimeLimit());
		policy.putNotNull(policyFields[7], param.getFsizeMin());
		policy.putNotNull(policyFields[8], param.getFsizeMax());
		policy.putNotNull(policyFields[9], param.getDeleteAfterDays());

		String token = auth.uploadToken(param.getBucketName(), param.getFileKey(), param.getExpires(), policy, true);
		result.put("uptoken", token);
		result.put("dir", param.getUploadDir());
		
		return result;
	}

	@Override
	public void close() {}

	@Override
	public String name() {
		return NAME;
	}
	
	private void processQiniuException(String bucketName, QiniuException e) {
		Response r = e.response;
		if(e.code() == 631){
			throw new JeesuiteBaseException(404, "bucketName["+bucketName+"]不存在");
		}
		if(e.code() == 614){
			throw new JeesuiteBaseException(406, "bucketName["+bucketName+"]已存在");
		}
		if(e.code() == 612){
			throw new JeesuiteBaseException(404, "资源不存在");
		}
		String message;
		try {
			message = r.bodyString();
		} catch (Exception e2) {
			message = r.toString();
		}
		throw new JeesuiteBaseException(message);
	}


	private String getUpToken(String bucketName,Map<String, Object> metadata) {
		if(metadata != null && !metadata.isEmpty()){
			return auth.uploadToken(bucketName);
		}
		long currentTime = System.currentTimeMillis();
		ExpireableUpToken token = bucketUploadTokenCache.get(bucketName);
		if(token != null && token.expiredAt > currentTime){
			return token.token;
		}
		synchronized (bucketUploadTokenCache) {
			String uploadToken = auth.uploadToken(bucketName);
			bucketUploadTokenCache.put(bucketName, new ExpireableUpToken(uploadToken, currentTime + 3500));
			return uploadToken;
		}
	}

	@Override
	protected String getFullPath(String bucketName, String file) {
		bucketName = currentBucketName(bucketName);
		if(file.startsWith(HTTP_PREFIX) || file.startsWith(HTTPS_PREFIX)){
			return file;
		}
		return getBucketUrlPrefix(bucketName).concat(file);
	}

	protected String buildBucketUrlPrefix(String buketName){
		String rs = "";
		String path = "/v6/domain/list?tbl="+buketName+"\n";
        String accessToken = auth.sign(path);
        String url = "http://api.qiniu.com/v6/domain/list?tbl="+buketName;                
        
        OkHttpClient client = new OkHttpClient();       
        Request request = new Request.Builder().url(url)
        		 .addHeader("Host", "api.qiniu.com")
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("Authorization", "QBox " + accessToken).build();
        okhttp3.Response re = null;
        try {
            re = client.newCall(request).execute();
            if (re.isSuccessful() == true) {
            	String[] reArr = JsonUtils.toObject(re.body().string(), String[].class);
            	if(reArr.length > 0){
            		rs = reArr[0];
            		if(!rs.endsWith(FilePathHelper.DIR_SPLITER)){
            			rs = rs.concat(FilePathHelper.DIR_SPLITER);
            		}
            	}
            } else {
                throw new JeesuiteBaseException(re.message());
            }
        } catch (IOException e) {
        	throw new JeesuiteBaseException(e.getMessage());
        }
        return rs;
	}

	private class ExpireableUpToken {
		String token;
		long expiredAt;
		/**
		 * @param token
		 * @param expiredAt
		 */
		public ExpireableUpToken(String token, long expiredAt) {
			super();
			this.token = token;
			this.expiredAt = expiredAt;
		}
	}

}
