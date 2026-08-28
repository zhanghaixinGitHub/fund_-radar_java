package com.fundradar.core.watchlist.service;

/** 当前用户未关注目标基金时，拒绝访问仅面向关注列表的完整详情。 */
public class WatchlistRequiredException extends RuntimeException {
}
