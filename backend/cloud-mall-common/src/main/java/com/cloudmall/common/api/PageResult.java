package com.cloudmall.common.api;
import java.util.*;
public class PageResult<T>{ public List<T> items; public int page; public int pageSize; public long total; public PageResult(List<T> i,int p,int s,long t){items=i;page=p;pageSize=s;total=t;} }
