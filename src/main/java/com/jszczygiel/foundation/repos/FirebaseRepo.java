package com.jszczygiel.foundation.repos;

import com.jszczygiel.foundation.repos.interfaces.BaseModel;
import com.jszczygiel.foundation.repos.interfaces.Repo;
import rx.Observable;

public interface FirebaseRepo<T extends BaseModel> extends Repo<T> {

  String getUserId();

  void setUserId(String userId);

  boolean isPublic();

  Observable<T> get(String id, String referenceId, boolean forceFresh);

  Observable<T> get(String id, String referenceId);

  void update(String refernceId, T model);

  void update(String referenceId, String id, T model);
}
