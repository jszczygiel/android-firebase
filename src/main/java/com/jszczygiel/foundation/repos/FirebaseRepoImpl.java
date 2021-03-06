package com.jszczygiel.foundation.repos;

import android.text.TextUtils;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Query;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.Transaction.Handler;
import com.google.firebase.database.Transaction.Result;
import com.google.firebase.database.ValueEventListener;
import com.jszczygiel.foundation.containers.Tuple;
import com.jszczygiel.foundation.enums.SubjectAction;
import com.jszczygiel.foundation.helpers.L;
import com.jszczygiel.foundation.repos.interfaces.BaseModel;
import com.jszczygiel.foundation.rx.schedulers.SchedulerHelper;
import dagger.Lazy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import rx.Emitter;
import rx.Observable;
import rx.exceptions.OnErrorNotImplementedException;
import rx.functions.Action1;
import rx.functions.Cancellable;
import rx.functions.Func1;
import rx.subjects.PublishSubject;

public abstract class FirebaseRepoImpl<T extends BaseModel> implements FirebaseRepo<T> {

  private final PublishSubject<Tuple<Integer, T>> subject;
  private final PublishSubject<List<T>> collectionSubject;
  protected String userId;
  private ChildEventListener reference;
  private final Lazy<FirebaseDatabase> firebaseDatabase;

  public FirebaseRepoImpl(Lazy<FirebaseDatabase> firebaseDatabase) {
    this.firebaseDatabase = firebaseDatabase;
    this.collectionSubject = PublishSubject.create();
    this.subject = PublishSubject.create();
  }

  public abstract String getTableName();

  @Override
  public String getUserId() {
    return userId;
  }

  @Override
  public void setUserId(String userId) {
    final boolean userIdChanged = !userId.equals(this.userId);
    this.userId = userId;
    init(userIdChanged);
  }

  private void init(boolean userIdChanged) {
    if (userIdChanged && reference != null) {
      getReference().removeEventListener(reference);
      reference = null;
    }
    if (reference == null && withRemoteListener()) {
      reference =
          getReference()
              .addChildEventListener(
                  new ChildEventListener() {
                    @Override
                    public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                      if (subject.hasObservers()) {
                        subject.onNext(new Tuple<>(SubjectAction.ADDED, create(dataSnapshot)));
                      }
                    }

                    @Override
                    public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                      if (subject.hasObservers()) {
                        subject.onNext(new Tuple<>(SubjectAction.CHANGED, create(dataSnapshot)));
                      }
                    }

                    @Override
                    public void onChildRemoved(DataSnapshot dataSnapshot) {
                      if (subject.hasObservers()) {
                        subject.onNext(new Tuple<>(SubjectAction.REMOVED, create(dataSnapshot)));
                      }
                    }

                    @Override
                    public void onChildMoved(DataSnapshot dataSnapshot, String s) {}

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                      getReference().removeEventListener(reference);
                      L.print(databaseError.toException());
                    }
                  });
    }
  }

  public boolean withRemoteListener() {
    return true;
  }

  protected DatabaseReference getReference() {
    if (isPublic()) {
      return firebaseDatabase.get().getReference(getTableName());
    } else {
      return firebaseDatabase.get().getReference(getTableName()).child(userId);
    }
  }

  public T create(DataSnapshot dataSnapshot) {
    return dataSnapshot.getValue(getType());
  }

  public abstract Class<T> getType();

  private Observable<T> getFresh(final String id, final String referenceId) {
    return Observable.create(
        new Action1<Emitter<T>>() {
          @Override
          public void call(final Emitter<T> emitter) {
            final DatabaseReference localReference =
                firebaseDatabase.get().getReference(getTableName()).child(referenceId).child(id);
            localReference.runTransaction(
                new Handler() {
                  @Override
                  public Result doTransaction(MutableData mutableData) {
                    return Transaction.success(mutableData);
                  }

                  @Override
                  public void onComplete(
                      DatabaseError databaseError, boolean b, DataSnapshot dataSnapshot) {
                    if (databaseError == null) {
                      T model = create(dataSnapshot);
                      if (model != null) {
                        emitter.onNext(model);
                      }
                      emitter.onCompleted();
                    } else {
                      emitter.onError(databaseError.toException());
                    }
                  }
                });
          }
        },
        Emitter.BackpressureMode.BUFFER);
  }

  private Observable<T> getStale(final String id, final String referenceId) {
    return Observable.create(
        new Action1<Emitter<T>>() {
          @Override
          public void call(final Emitter<T> emitter) {
            final Query localReference =
                firebaseDatabase
                    .get()
                    .getReference(getTableName())
                    .child(referenceId)
                    .child(id)
                    .orderByKey();
            final ValueEventListener listener =
                new ValueEventListener() {
                  @Override
                  public void onDataChange(DataSnapshot dataSnapshot) {
                    T model = create(dataSnapshot);
                    if (model != null) {
                      emitter.onNext(model);
                    }
                    emitter.onCompleted();
                  }

                  @Override
                  public void onCancelled(DatabaseError databaseError) {
                    emitter.onError(databaseError.toException());
                    localReference.removeEventListener(this);
                  }
                };
            emitter.setCancellation(
                new Cancellable() {
                  @Override
                  public void cancel() throws Exception {
                    localReference.removeEventListener(listener);
                  }
                });
            localReference.addListenerForSingleValueEvent(listener);
          }
        },
        Emitter.BackpressureMode.BUFFER);
  }

  @Override
  public Observable<T> get(final String id, final String referenceId, boolean forceFresh) {
    L.d("firebase:" + this.getClass().toString() + " get:" + id);
    checkPreConditions();
    if (TextUtils.isEmpty(id)) {
      throw new DatabaseException("no valid itemId");
    }
    if (forceFresh) {
      return getFresh(id, referenceId)
          .timeout(300, TimeUnit.MILLISECONDS)
          .onErrorResumeNext(getStale(id, referenceId));
    } else {
      return getStale(id, referenceId);
    }
  }

  @Override
  public Observable<T> get(final String id, final String referenceId) {
    return get(id, referenceId, true);
  }

  @Override
  public Observable<T> get(final String id) {
    return get(id, userId);
  }

  @Override
  public Observable<T> getAll() {
    L.d("firebase:" + this.getClass().toString() + " getAll");
    checkPreConditions();

    return Observable.create(
        new Action1<Emitter<T>>() {
          @Override
          public void call(final Emitter<T> emitter) {
            final Query localReference = getReference().orderByKey();
            final ValueEventListener listener =
                new ValueEventListener() {
                  @Override
                  public void onDataChange(DataSnapshot dataSnapshot) {
                    try {
                      for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                        T model = create(snapshot);
                        if (model != null) {
                          emitter.onNext(model);
                        }
                      }
                      emitter.onCompleted();
                    } catch (DatabaseException exception) {
                      emitter.onError(exception);
                    }
                  }

                  @Override
                  public void onCancelled(DatabaseError databaseError) {
                    emitter.onError(databaseError.toException());
                    localReference.removeEventListener(this);
                  }
                };
            emitter.setCancellation(
                new Cancellable() {
                  @Override
                  public void cancel() throws Exception {
                    localReference.removeEventListener(listener);
                  }
                });
            localReference.addListenerForSingleValueEvent(listener);
          }
        },
        Emitter.BackpressureMode.BUFFER);
  }

  @Override
  public void notify(T model) {
    L.d("firebase:" + this.getClass().toString() + " notify");

    if (subject.hasObservers()) {
      subject.onNext(new Tuple<>(SubjectAction.CHANGED, model));
    }
  }

  protected void add(T model) {
    L.d("firebase:" + this.getClass().toString() + " add");

    checkPreConditions();
    getReference().child(model.id()).setValue(model);
  }

  protected void checkPreConditions() {
    if (TextUtils.isEmpty(userId) && !isPublic()) {
      throw new DatabaseException("no valid userId");
    }
  }

  @Override
  public Observable<T> remove(final String id) {
    L.d("firebase:" + this.getClass().toString() + " remove");
    checkPreConditions();
    return get(id)
        .observeOn(SchedulerHelper.databaseWriterScheduler())
        .map(
            new Func1<T, T>() {
              @Override
              public T call(T map) {
                FirebaseRepoImpl.this.getReference().child(id).removeValue();
                return map;
              }
            });
  }

  protected void update(final T model) {
    checkPreConditions();
    L.d("firebase:" + this.getClass().toString() + " update");
    get(model.id())
        .observeOn(SchedulerHelper.databaseWriterScheduler())
        .subscribe(
            new Action1<T>() {
              @Override
              public void call(T next) {
                FirebaseRepoImpl.this.getReference().updateChildren(model.toMap(next));
              }
            },
            new Action1<Throwable>() {
              @Override
              public void call(Throwable throwable) {
                throw new OnErrorNotImplementedException(throwable);
              }
            });
  }

  @Override
  public void update(String referenceId, T model) {
    firebaseDatabase
        .get()
        .getReference(getTableName())
        .child(referenceId)
        .updateChildren(model.toMap());
  }

  @Override
  public void update(String referenceId, String id, T model) {
    firebaseDatabase
        .get()
        .getReference(getTableName())
        .child(referenceId)
        .child(id)
        .updateChildren(model.toMap());
  }

  @Override
  public void put(final T model) {
    get(model.id())
        .count()
        .subscribe(
            new Action1<Integer>() {
              @Override
              public void call(Integer next) {
                if (next == 0) {
                  add(model);
                } else {
                  update(model);
                }
              }
            });
  }

  @Override
  public Observable<Tuple<Integer, T>> observe() {
    return subject;
  }

  @Override
  public Observable<List<T>> observeAll() {
    return collectionSubject;
  }

  @Override
  public void clear() {
    L.d("firebase:" + this.getClass().toString() + " update");

    checkPreConditions();
    getReference().removeValue();
  }
}
