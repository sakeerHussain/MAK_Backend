package com.vehiclerental.mak.common;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StoredUploads {
 private final Path root;
 public StoredUploads(@Value("${app.upload-dir}") String directory){root=Path.of(directory).toAbsolutePath().normalize();}
 public Metadata store(String folder,long id,MultipartFile upload) {return store(folder,id,upload,false);}
 public Metadata storeImage(String folder,long id,MultipartFile upload) {return store(folder,id,upload,true);}
 private Metadata store(String folder,long id,MultipartFile upload,boolean imageOnly) {
  if(upload==null || upload.isEmpty() || upload.getSize()>5L*1024*1024)throw bad("Choose a non-empty file up to 5 MB.");
  String type,extension;
  try(InputStream stream=upload.getInputStream()){
   byte[] b=stream.readNBytes(12);
   if(b.length>=8 && Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10})){type="image/png";extension=".png";}
   else if(b.length>=3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255){type="image/jpeg";extension=".jpg";}
   else if(b.length>=12 && new String(b,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(b,8,4,StandardCharsets.US_ASCII).equals("WEBP")){type="image/webp";extension=".webp";}
   else if(b.length>=4 && new String(b,0,4,StandardCharsets.US_ASCII).equals("%PDF")){type="application/pdf";extension=".pdf";}
   else throw bad("Unsupported file. Choose PNG, JPG, WebP or PDF.");
  }catch(IOException e){throw new IllegalStateException("Unable to inspect receipt.",e);}
  if(imageOnly && type.equals("application/pdf"))throw bad("Inspection photos must be PNG, JPG or WebP images.");
  String key=UUID.randomUUID()+extension;Path path=path(folder,id,key);
  try{
   Files.createDirectories(path.getParent());try(InputStream stream=upload.getInputStream()){Files.copy(stream,path);}
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status==STATUS_ROLLED_BACK)remove(path);}});
  }catch(IOException|RuntimeException e){remove(path);throw new IllegalStateException("Unable to save receipt.",e);}
  String name=Objects.toString(upload.getOriginalFilename(),"receipt").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\r\\n\\t]"," ");
  if(name.isBlank())name="receipt";if(name.length()>255)name=name.substring(0,255);
  return new Metadata(key,name,type,upload.getSize());
 }
 public Resource resource(String folder,long id,String key){Path path=path(folder,id,key);if(!Files.isRegularFile(path))throw new ApiException(HttpStatus.NOT_FOUND,"Receipt unavailable.");return new FileSystemResource(path);}
 public void deleteAfterCommit(String folder,long id,String key){if(key==null)return;Path path=path(folder,id,key);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){remove(path);}});}
 private Path path(String folder,long id,String key){Path p=root.resolve(folder).resolve(String.valueOf(id)).resolve(key).normalize();if(!p.startsWith(root.resolve(folder).resolve(String.valueOf(id))))throw bad("Invalid receipt path.");return p;}
 private void remove(Path p){try{Files.deleteIfExists(p);}catch(IOException ignored){}}
 private ApiException bad(String s){return new ApiException(HttpStatus.BAD_REQUEST,s);}
 public record Metadata(String key,String name,String type,long size){}
}
