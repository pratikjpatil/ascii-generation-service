package com.tcs.fincore.AsciiGenerationService.Service;
import jakarta.annotation.PostConstruct;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import java.net.URI;

@Slf4j
@Service
public class HdfsService{
    @Value("${hadoop.fs.uri}")
    private String hdfsUri;
     
    @Value("${hadoop.fs.user}")
    private String hdfsUser;

    private FileSystem fs;

    @PostConstruct
    public void init(){
        try{
            Configuration conf= new Configuration();

            fs=FileSystem.get(new URI(hdfsUri), conf, hdfsUser);
            log.info("Connection to hdfs as user: {}",hdfsUser);

             if(hdfsUri.startsWith("file"))
             {
                 conf.set("fs.defaultFS",hdfsUri);
                 fs = FileSystem.get(conf);
                 log.info("Using local fs: {}",hdfsUri);
             }
             else{
             }
           
        }
        catch(Exception e){
            log.error("HDFS connection failed",e);
        }
    }

    public FileSystem getFs(){
        return fs;
    }
}