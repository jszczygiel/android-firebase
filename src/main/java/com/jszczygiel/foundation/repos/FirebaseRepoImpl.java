package com.jszczygiel.foundation.repos;

import android.text.TextUtils;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.jszczygiel.foundation.containers.Tuple;
import com.jszczygiel.foundation.enums.SubjectAction;
import com.jszczygiel.foundation.helpers.LoggerHelper;
import com.jszczygiel.foundation.repos.interfaces.BaseModel;
import com.jszczygiel.foundation.repos.interfaces.Repo;
import com.jszczygiel.foundation.rx.PublishSubject;
import com.jszczygiel.foundation.rx.retry.RetryBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.schedulers.Schedulers;

public abstract class FirebaseRepoImpl<T extends BaseModel> implements Repo<T> {

    protected final DatabaseReference table;
    private final PublishSubject<Tuple<Integer, T>> subject;
    private final PublishSubject<List<T>> collectionSubject;
    private final Map<String, T> models;
    protected String userId;
    private ChildEventListener reference;

    public FirebaseRepoImpl() {
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        table = database.getReference(getTableName());
        collectionSubject = PublishSubject.createWith(PublishSubject.BUFFER);
        models = new ConcurrentHashMap<>();
        subject = PublishSubject.createWith(PublishSubject.BUFFER);


    }

    public abstract String getTableName();

    @Override
    public void setUserId(String userId) {
        this.userId = userId;
        init();
    }

    private void init() {
        if (reference == null) {
            reference = getReference().addChildEventListener(new ChildEventListener() {
                @Override
                public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                    addInternal(dataSnapshot.getValue(getType()));
                }

                @Override
                public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                    updateInternal(dataSnapshot.getValue(getType()));
                }

                @Override
                public void onChildRemoved(DataSnapshot dataSnapshot) {
                    removeInternal(dataSnapshot.getValue(getType()).getId());
                }

                @Override
                public void onChildMoved(DataSnapshot dataSnapshot, String s) {

                }

                @Override
                public void onCancelled(DatabaseError databaseError) {
                    LoggerHelper.log(databaseError.toException());
                }
            });
        }
    }

    protected DatabaseReference getReference() {
        if (isPublic()) {
            return table;
        } else {
            return table.child(userId);
        }
    }

    protected void addInternal(T model) {
        models.put(model.getId(), model);
        subject.onNext(new Tuple<>(SubjectAction.ADDED, model));
    }

    public abstract Class<T> getType();

    protected void updateInternal(T model) {
        models.put(model.getId(), model);
        subject.onNext(new Tuple<>(SubjectAction.CHANGED, model));
    }

    protected void removeInternal(String id) {
        subject.onNext(new Tuple<>(SubjectAction.REMOVED, models.remove(id)));
    }

    @Override
    public Observable<T> get(final String id) {
        return Observable.create(new Observable.OnSubscribe<T>() {
            @Override
            public void call(final Subscriber<? super T> subscriber) {
                if (models.get(id) == null) {
                    getReference().child(id).addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot dataSnapshot) {
                            T model = dataSnapshot.getValue(getType());
                            if (model != null) {
                                addInternal(model);
                                subscriber.onNext(model);
                            }
                            subscriber.onCompleted();
                        }

                        @Override
                        public void onCancelled(DatabaseError databaseError) {
                            subscriber.onError(databaseError.toException());
                        }

                    });
                } else {
                    subscriber.onNext(models.get(id));
                    subscriber.onCompleted();

                }
            }
        }).timeout(200, TimeUnit.SECONDS).retryWhen(RetryBuilder.any().max(15).build());
    }

    @Override
    public Observable<T> getAll() {
        return Observable.from(models.values());
    }

    @Override
    public void add(T model) {
        checkPreConditions();
        getReference().child(model.getId()).setValue(model);
    }

    protected void checkPreConditions() {
        if (TextUtils.isEmpty(userId) && !isPublic()) {
            throw new DatabaseException("no valid userId");
        }
    }

    @Override
    public Observable<T> remove(String id) {
        checkPreConditions();
        Observable<T> model = get(id);
        getReference().child(id).removeValue();
        return model;
    }

    @Override
    public void update(T model) {
        checkPreConditions();
        T oldModel = models.get(model.getId());
        if (oldModel != null && oldModel.equals(model)) {
            updateInternal(model);
        }
        getReference().child(model.getId()).setValue(model);
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
        checkPreConditions();
        getReference().removeValue();
    }

    @Override
    public int count() {
        return models.size();
    }

    protected Map<String, T> getModels() {
        return models;
    }
}
