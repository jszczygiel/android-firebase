package com.jszczygiel.foundation.repos;

import com.jszczygiel.foundation.repos.interfaces.BaseModel;
import com.jszczygiel.foundation.repos.interfaces.Repo;

import rx.Observable;

public interface FirebaseRepo<T extends BaseModel> extends Repo<T> {

  void setUserId(String userId);

  String getUserId();

  boolean isPublic();

  Observable<T> get(String id, String referenceId);

  void update(String refernceId, T model);
}
