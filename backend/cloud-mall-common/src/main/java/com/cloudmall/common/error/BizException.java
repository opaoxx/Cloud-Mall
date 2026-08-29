package com.cloudmall.common.error;
public class BizException extends RuntimeException { private final String code; private final int httpStatus; public BizException(String c,String m,int s){super(m);code=c;httpStatus=s;} public String getCode(){return code;} public int getHttpStatus(){return httpStatus;} }
